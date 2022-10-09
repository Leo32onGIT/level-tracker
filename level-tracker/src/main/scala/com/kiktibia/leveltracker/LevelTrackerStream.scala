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

  private lazy val sourceTick = Source.tick(2.seconds, 60.seconds, ()) // im kinda cow-boying it here

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val online: List[(String, Double)] = worldResponse.worlds.world.online_players.map(i => (i.name, i.level))
    //val filtered = arrayTuple.filter(t => numbers.contains(t._1))
    recentOnline.filterInPlace(i => !online.contains(i._1)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => (i._1, i._2))) // ...and add them again, with an updated online time
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

          /***
          for (l <- recentLevels
            //if l.char == name && l.level < olLevel ){
            if olName == l.char){
              println("recentLevels:")
              println(l)
          };
          ***/

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
            recentLevels.add(charLevel)
            Some(CharLevel(char, olLevel))
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
      var guildText = ""

      // guild
      // does player have guild?
      var guildIcon = Config.otherGuild
      if (guildName != "") {
        // is player an ally
        val allyGuilds = Config.allyGuilds.contains(guildName.toLowerCase())
        if (allyGuilds == true){
          embedColor = 36941 // bright green
          guildIcon = Config.allyGuild
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
        guildText = s"$guildIcon *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})\n"
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
      val embedText = s"$guildText Advanced to level **${charLevel.level.toInt}**."

      val embed = new EmbedBuilder()
      embed.setTitle(s"${vocEmoji(charLevel.char)} $charName ${vocEmoji(charLevel.char)}", charUrl(charName))
      embed.setDescription(embedText)
      // embed.setThumbnail(embedThumbnail)
      embed.setColor(embedColor)
      embed.build()
    }
    // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
    embeds.foreach { embed =>
      levelsChannel.sendMessageEmbeds(embed).queue()
    }
    /***
    if (notablePoke != ""){
      deathsChannel.sendMessage(notablePoke).queue();
    }
    ***/
    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    /***
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(ZonedDateTime.parse(i.lastLogin.get), now).getSeconds
      diff < onlineRecentDuration
    }
    ***/
    // private val recentLevels = mutable.Set.empty[CharKey]
    // case class CharKey(char: String, level: Double, lastLogin: Option[String])
    val onlineLevel: List[(String, Double)] = recentOnline.map(i => (i._1, i._2)).toList
    recentLevels.filterInPlace( i => !onlineLevel.contains(i.char) )

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
