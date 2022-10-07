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
  case class CharKey(char: String, level: Levels)

  case class CharLevel(char: CharacterResponse, level: Levels)

  private val recentLevels = mutable.Set.empty[CharKey]
  private val recentOnline = mutable.Set.empty[CharKey]

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
    val now = ZonedDateTime.now()
    val online: List[CharKey] = worldResponse.worlds.world.online_players.map(i => CharKey(i.name, Levels(now.toString, i.level)))
    recentOnline.filterInPlace(i => !online.contains(i.char)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => CharKey(i.char, Levels(now.toString, i.level.level)))) // ...and add them again, with an updated online time
    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
    Source(charsToCheck).mapAsyncUnordered(24)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForLevels = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
		val now = ZonedDateTime.now()
    val newLevels = characterResponses.flatMap { char =>

			val sheetLevel = char.characters.character.level
			val name = char.characters.character.name
			val onlineLevel: List[(String, Double)] = recentOnline.map(i => (i.char, i.level.level)).toList
			onlineLevel.flatMap { case (olName, olLevel) =>
				if (olName == name){
					val charLevel = CharKey(name, Levels(now.toString, olLevel))
					if (olLevel > sheetLevel && !recentLevels.contains(charLevel)){
						recentLevels.add(charLevel)
						Some(CharLevel(char, Levels(now.toString, olLevel)))
					}
					else None
				}
				else None
			}
			//val levels: List[CharKey] = char.map(i => CharKey(i.name, Levels(now.toString, i.level)))
			// recentLevels.filterInPlace(i => !levels.contains(i.char))
			/***
			levels.flatMap {l =>
				val charSheet = char.characters.character.level
				val charLevel = CharKey(char.characters.character.name, Levels(now.toString, l.level.level))
				if (l.level.level > charSheet && !recentLevels.contains(charLevel)){
					recentLevels.add(charLevel)
					Some(CharLevel(char, Levels(now.toString, l.level.level)))
				}
				else None
			}
			***/
			//if (char.characters.character.level < recentOnline[char])

			/***foreach { l =>
				if (l.level.level < recentOnline.)
			}

			deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.characters.character.name, deathTime)
			}

        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          Some(CharDeath(char, death))
        }
        else None

			val levels: List[CharKey] = List(Charkey(char.characters.character.name, Levels(now.toString, char.characters.character.level)))
      levels.flatMap { l =>
        logger.info(l.toString)


        if (levelData.level.level < recentLevels.contains) {
          recentLevels.add(charLevel)
					Some(CharLevel(char, charLevel.level))
        }
        else None
				***/
    }
    Future.successful(newLevels)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharLevel]].mapAsync(1) { charLevels =>

    /***
    // Filter only the interesting deaths (nemesis bosses, rare bestiary)
    val (notableDeaths, normalDeaths) = charDeaths.toList.partition { charDeath =>
      Config.notableCreatures.exists(c => c.endsWith(charDeath.death.killers.last.name.toLowerCase))
    }

    // logging
    logger.info(s"New notable deaths: ${notableDeaths.length}")
    notableDeaths.foreach(d => logger.info(s"${d.char.characters.character.name} - ${d.death.killers.last.name}"))
    logger.info(s"New normal deaths: ${normalDeaths.length}")
    normalDeaths.foreach(d => logger.info(s"${d.char.characters.character.name} - ${d.death.killers.last.name}"))


    val embeds = notableDeaths.sortBy(_.death.time).map { charDeath =>
    ***/

    // var notablePoke = ""
    val embeds = charLevels.toList.sortBy(_.level.time).map { charLevel =>
      val charName = charLevel.char.characters.character.name
      var embedColor = 3092790 // background default
      var embedThumbnail = creatureImageUrl("hunter")

      // guild rank and name
      val guild = charLevel.char.characters.character.guild
      val guildName = if(!(guild.isEmpty)) guild.head.name else ""
      val guildRank = if(!(guild.isEmpty)) guild.head.rank else ""
      var guildText = ":x: **No Guild**\n"

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
      val embedText = s"$guildText Advanced to level **${charLevel.level.level.toInt}**."

      val embed = new EmbedBuilder()
      embed.setTitle(s"${vocEmoji(charLevel.char)} $charName ${vocEmoji(charLevel.char)}", charUrl(charName))
      embed.setDescription(embedText)
      embed.setThumbnail(embedThumbnail)
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
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(ZonedDateTime.parse(i.level.time), now).getSeconds
      diff < onlineRecentDuration
    }
    recentLevels.filterInPlace { i =>
      val diff = java.time.Duration.between(ZonedDateTime.parse(i.level.time), now).getSeconds
      diff < levelRecentDuration
    }
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
