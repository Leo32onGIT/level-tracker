package com.kiktibia.leveltracker

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.kiktibia.leveltracker.tibiadata.TibiaDataClient
import com.kiktibia.leveltracker.tibiadata.response.{CharacterResponse, Levels, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.TextChannel

import java.time.ZonedDateTime
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._

class LevelTrackerStream(levelsChannel: TextChannel)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, level: Double, lastLogin: Option[String])

  case class CharLevel(char: CharacterResponse, level: Double)

  private val recentLevels = mutable.Set.empty[CharKey]
  private val recentOnline = mutable.Set.empty[(String, Double)]

  private val tibiaDataClient = new TibiaDataClient()

  private val levelRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the LevelTrackerStream:", e)
    Supervision.Resume
  }
  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)

  private lazy val sourceTick = Source.tick(2.seconds, 30.seconds, ()) // im kinda cow-boying it here

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val online: List[(String, Double)] = worldResponse.worlds.world.online_players.map(i => (i.name, i.level))
    recentOnline.filterInPlace(i => !online.map(_._1).contains(i._1)) // Remove existing online chars from the list...
    //recentOnline.filterInPlace(i => false) // idk what im doing, clearing the recentOnline list completely i guess
    recentOnline.addAll(online.map(i => (i._1, i._2))) // ...and add them again, with an updated level

    // DEBUG:
    println("recentOnline (String, Double):")
    for (l <- recentOnline){
      println(s"\t${l._1}, ${l._2.toInt}");
    }

    val charsToCheck: Set[String] = recentOnline.map(_._1).toSet
    Source(charsToCheck).mapAsyncUnordered(24)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForLevels = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val newLevels = characterResponses.flatMap { char =>
      val sheetLevel = char.characters.character.level
      val sheetLogin = char.characters.character.last_login
      val name = char.characters.character.name
      val onlineLevel: List[(String, Double)] = recentOnline.map(i => (i._1, i._2)).toList
      onlineLevel.flatMap { case (olName, olLevel) =>
        if (olName == name){

          // attempt to cleanup recentLevels
          for (l <- recentLevels
            // online char matches recentLevels entry
            if olName == l.char){
              //println(l)
              val lastLoginCheck = l.lastLogin.getOrElse("") // safety?
              if (lastLoginCheck != ""){
                // if player didn't relog
                // recentLevel.level entry is greater than online level
                // charactersheet last_login matches recentLevel
                /***
                if (l.level > olLevel && l.lastLogin.get == sheetLogin.getOrElse("2022-01-01T01:00:00Z")) {
                  println(s"Died and stayed logged in:")
                  println(l)
                  recentLevels.remove(l)
                }
                ***/
                // recentLevel.level entry is greater than online level
                // charactersheet last_login greater than recentLevel.lastLogin entry
                if (l.level > olLevel && ZonedDateTime.parse(l.lastLogin.get).isBefore(ZonedDateTime.parse(sheetLogin.getOrElse("2022-01-01T01:00:00Z")))) {
                  println(s"Online /w Level Entry:\n OL: $olName, $olLevel, ${sheetLogin.getOrElse("Invalid")}\n RL: ${l.char}, ${l.level}, ${l.lastLogin.getOrElse("Invalid")}")
                  println(s"Relogged:")
                  println(l)
                  recentLevels.remove(l)
                }
              }
          };

          /***
          // "2022-01-01T01:00:00Z"
          // remove older levels
          for (l <- recentLevels
            //if l.char == name && l.level < olLevel ){
            if olName == l.char){
              println("recentLevels:")
              println(l)
              // need to use last_login here i think
              val recentLogin = l.lastLogin.getOrElse("2022-01-01T01:00:00Z")
              val currentLogin = sheetLogin.getOrElse("2022-01-01T01:00:00Z")
              if (olLevel > l.level && ZonedDateTime.parse(recentLogin).isBefore(ZonedDateTime.parse(currentLogin))) {
                //println(recentLogin)
                //println(currentLogin)
                //println("TRIGGERED")
                //recentLevels -= l
              }
          };
          ***/

          val charLevel = CharKey(olName, olLevel, sheetLogin)
          if (olLevel > sheetLevel && !recentLevels.contains(charLevel)) {
            val guild = char.characters.character.guild
            val guildName = if(!(guild.isEmpty)) guild.head.name else ""
            if (olLevel > 250 || Config.huntedGuilds.contains(guildName.toLowerCase()) || Config.allyGuilds.contains(guildName.toLowerCase()) || Config.allyPlayers.contains(name.toLowerCase()) || Config.huntedPlayers.contains(name.toLowerCase())) {
              recentLevels.add(charLevel)
              Some(CharLevel(char, olLevel))
            }
            else None
          }
          else None
        }
        else None
      }
    }
    Future.successful(newLevels)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharLevel]].mapAsync(1) { charLevels =>

    val embeds = charLevels.toList.sortBy(_.level).map { charLevel =>
      val charName = charLevel.char.characters.character.name
      var embedColor = 3092790 // background default
      var embedThumbnail = creatureImageUrl("hunter")

      // guild rank and name
      val guild = charLevel.char.characters.character.guild
      val guildName = if(!(guild.isEmpty)) guild.head.name else ""
      val guildRank = if(!(guild.isEmpty)) guild.head.rank else ""
      //var guildText = ""

      // guild
      // does player have guild?
      if (guildName != "") {
        //var guildIcon = Config.otherGuild
        // is player an ally
        val allyGuilds = Config.allyGuilds.contains(guildName.toLowerCase())
        if (allyGuilds == true){
          embedColor = 36941 // bright green
          //guildIcon = Config.allyGuild
        }
        // is player in hunted guild
        val huntedGuilds = Config.huntedGuilds.contains(guildName.toLowerCase())
        if (huntedGuilds == true){
          embedColor = 13773097 // bright red
          /***
          if (charLevel.level.level.toInt >= 250) {
            notablePoke = Config.inqBlessRole // PVE fullbless opportuniy (only poke for level 250+)
          }
          ***/
        }
        //guildText = s"\n$guildIcon *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})"

        //guildText = guildIcon
      }

      // player
      // ally player
      val allyPlayers = Config.allyPlayers.contains(charName.toLowerCase())
      if (allyPlayers == true){
        embedColor = 36941 // bright green
      }
      // hunted player
      val huntedPlayers = Config.huntedPlayers.contains(charName.toLowerCase())
      if (huntedPlayers == true){
        embedColor = 13773097 // bright red bright green
      }

      //val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond

      // this is the actual embed description
      val embedText = s"${vocEmoji(charLevel.char)} **[$charName](${charUrl(charName)})** ${vocEmoji(charLevel.char)} advanced to level **${charLevel.level.toInt}**"

      //if (embedColor != 3092790 || charLevel.level.toInt > 250) { // only show hunted/ally or neutrals over level 250
      new EmbedBuilder()
      //.setTitle(s"${vocEmoji(charLevel.char)} $charName ${vocEmoji(charLevel.char)}", charUrl(charName))
      .setDescription(embedText)
      // embed.setThumbnail(embedThumbnail)
      .setColor(embedColor)
      .build()
    }
    // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
    //embeds.foreach { embed =>
      //levelsChannel.sendMessageEmbeds(embed).queue()
    //}

    if (embeds.nonEmpty) {
      levelsChannel.sendMessageEmbeds(embeds.asJava).queue()
    }

    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {

    /***
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(ZonedDateTime.parse(i.lastLogin.get), now).getSeconds
      diff < onlineRecentDuration
    }
    ***/
    // private val recentLevels = mutable.Set.empty[CharKey]
    // case class CharKey(char: String, level: Double, lastLogin: Option[String])
/***
    val onlineLevel: List[(String, Double)] = recentOnline.map(i => (i._1, i._2)).toList
    recentLevels.filterInPlace( i => !onlineLevel.contains(i.char) )
***/
    /***
    recentLevels.filterInPlace{ i =>
      val name = i.char
      val level = i.level
      val lastLogin = i.lastLogin.getOrElse("2022-01-01T01:00:00Z")
      val onlineLevel: List[(String, Double)] = recentOnline.map(i => (i._1, i._2)).toList
      val check = onlineLevel.flatMap { case (olName, olLevel) =>
        if (olName == name){
          if (level > olLevel){
            //!online.contains(i._1)
            Some(name, level, lastLogin)
          }
          else None
        }
        else None
      }
      //
    }
    *///
  }

  private def vocEmoji(char: CharacterResponse): String = {
    val voc = char.characters.character.vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => ":shield:"
      case "druid" => ":snowflake:"
      case "sorcerer" => ":fire:"
      case "paladin" => ":bow_and_arrow:"
      case "none" => ":hatching_chick:"
      case _ => ""
    }
  }

  private def charUrl(char: String): String =
    s"https://www.tibia.com/community/?name=${char.replaceAll(" ", "+")}"

  private def creatureImageUrl(creature: String): String = {
    val finalCreature = Config.creatureUrlMappings.getOrElse(creature.toLowerCase, {
      // Capitalise the start of each word, including after punctuation e.g. "Mooh'Tah Warrior", "Two-Headed Turtle"
      val rx1 = """([^\w]\w)""".r
      val parsed1 = rx1.replaceAllIn(creature, m => m.group(1).toUpperCase)

      // Lowercase the articles, prepositions etc., e.g. "The Voice of Ruin"
      val rx2 = """( A| Of| The| In| On| To| And| With| From)(?=( ))""".r
      val parsed2 = rx2.replaceAllIn(parsed1, m => m.group(1).toLowerCase)

      // Replace spaces with underscores and make sure the first letter is capitalised
      parsed2.replaceAll(" ", "_").capitalize
    })
    s"https://tibia.fandom.com/wiki/Special:Redirect/file/$finalCreature.gif"
  }

  lazy val stream: RunnableGraph[Cancellable] =
    sourceTick via
      getWorld via
      getCharacterData via
      scanForLevels via
      postToDiscordAndCleanUp to Sink.ignore

}
