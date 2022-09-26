package com.kiktibia.deathtracker

import akka.actor.Cancellable
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.scaladsl.{Flow, RunnableGraph, Sink, Source}
import akka.stream.{Attributes, Materializer, Supervision}
import com.kiktibia.deathtracker.tibiadata.TibiaDataClient
import com.kiktibia.deathtracker.tibiadata.response.{CharacterResponse, Deaths, WorldResponse}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.TextChannel

import java.time.ZonedDateTime
import scala.collection.immutable.HashMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters._

class DeathTrackerStream(deathsChannel: TextChannel)(implicit ex: ExecutionContextExecutor, mat: Materializer) extends StrictLogging {

  // A date-based "key" for a character, used to track recent deaths and recent online entries
  case class CharKey(char: String, time: ZonedDateTime)

  case class CharDeath(char: CharacterResponse, death: Deaths)

  private val recentDeaths = mutable.Set.empty[CharKey]
  private val recentOnline = mutable.Set.empty[CharKey]

  private val tibiaDataClient = new TibiaDataClient()

  private val deathRecentDuration = 30 * 60 // 30 minutes for a death to count as recent enough to be worth notifying
  private val onlineRecentDuration = 10 * 60 // 10 minutes for a character to still be checked for deaths after logging off

  private val logAndResumeDecider: Supervision.Decider = { e =>
    logger.error("An exception has occurred in the DeathTrackerStream:", e)
    Supervision.Resume
  }
  private val logAndResume: Attributes = supervisionStrategy(logAndResumeDecider)

  private lazy val sourceTick = Source.tick(2.seconds, 120.seconds, ())

  private lazy val getWorld = Flow[Unit].mapAsync(1) { _ =>
    logger.info("Running stream")
    tibiaDataClient.getWorld() // Pull all online characters
  }.withAttributes(logAndResume)

  private lazy val getCharacterData = Flow[WorldResponse].mapAsync(1) { worldResponse =>
    val now = ZonedDateTime.now()
    val online: List[String] = worldResponse.worlds.world.online_players.map(_.name)
    recentOnline.filterInPlace(i => !online.contains(i.char)) // Remove existing online chars from the list...
    recentOnline.addAll(online.map(i => CharKey(i, now))) // ...and add them again, with an updated online time
    val charsToCheck: Set[String] = recentOnline.map(_.char).toSet
    Source(charsToCheck).mapAsyncUnordered(16)(tibiaDataClient.getCharacter).runWith(Sink.collection).map(_.toSet)
  }.withAttributes(logAndResume)

  private lazy val scanForDeaths = Flow[Set[CharacterResponse]].mapAsync(1) { characterResponses =>
    val now = ZonedDateTime.now()
    val newDeaths = characterResponses.flatMap { char =>
      val deaths: List[Deaths] = char.characters.deaths.getOrElse(List.empty)
      deaths.flatMap { death =>
        val deathTime = ZonedDateTime.parse(death.time)
        val deathAge = java.time.Duration.between(deathTime, now).getSeconds
        val charDeath = CharKey(char.characters.character.name, deathTime)
        if (deathAge < deathRecentDuration && !recentDeaths.contains(charDeath)) {
          recentDeaths.add(charDeath)
          Some(CharDeath(char, death))
        }
        else None
      }
    }
    Future.successful(newDeaths)
  }.withAttributes(logAndResume)

  private lazy val postToDiscordAndCleanUp = Flow[Set[CharDeath]].mapAsync(1) { charDeaths =>

    // Filter only the interesting deaths (nemesis bosses, rare bestiary)
    /***
    val notableDeaths: List[CharDeath] = charDeaths.toList.filter { charDeath =>
      Config.notableCreatures.exists(c => c.endsWith(charDeath.death.killers.last.name.toLowerCase))
    }


    val embeds = notableDeaths.sortBy(_.death.time).map { charDeath =>
    ***/
    var notablePoke = ""
    val embeds = charDeaths.toList.sortBy(_.death.time).map { charDeath =>
      val charName = charDeath.char.characters.character.name
      val killer = charDeath.death.killers.last.name
      var embedThumbnail = creatureImageUrl(killer)

      var bossIcon = ""
      // nemesis icon
      val nemesis = Config.nemesisCreatures.contains(killer.toLowerCase())
      if (nemesis == true){
        bossIcon = Config.nemesisEmoji ++ " "
      }
      // archfoe icon
      val archfoe = Config.archfoeCreatures.contains(killer.toLowerCase())
      if (archfoe == true){
        bossIcon = Config.archfoeEmoji ++ " "
      }
      // bane icon
      val bane = Config.baneCreatures.contains(killer.toLowerCase())
      if (bane == true){
        bossIcon = Config.baneEmoji ++ " "
      }

      // guild rank and name
      var embedColor = 3553599 // background default
      val guild = charDeath.char.characters.character.guild
      val guildName = if(!(guild.isEmpty)) guild.head.name else ""
      val guildRank = if(!(guild.isEmpty)) guild.head.rank else ""
      var guildText = "**No Guild** :x:\n"

      // check if death was by another player
      val pvp = charDeath.death.killers.last.player
      var context = "Died"
      if (pvp == true) {
         context = "Killed"
         embedColor = 14869218 // bone white
         embedThumbnail = creatureImageUrl("Phantasmal_Ooze")
      }

      // guild
      // does player have guild?
      if (guildName != "") {
        // is player an ally
        val allyGuilds = Config.allyGuilds.contains(guildName.toLowerCase())
        if (allyGuilds == true){
          embedColor = 13773097 // bright red
        }
        // is player in hunted guild
        val huntedGuilds = Config.huntedGuilds.contains(guildName.toLowerCase())
        if (huntedGuilds == true){
          embedColor = 36941 // bright green
        }
        guildText = s"**Guild** :white_check_mark: *$guildRank* of the [$guildName](https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=${guildName.replace(" ", "%20")})\n"
      }

      // player
      // ally player
      val allyPlayers = Config.allyPlayers.contains(charName.toLowerCase())
      if (allyPlayers == true){
        embedColor = 13773097 // bright red
      }
      // hunted player
      val huntedPlayers = Config.huntedPlayers.contains(charName.toLowerCase())
      if (huntedPlayers == true){
        embedColor = 36941 // bright green
      }

      // poke if killer is in notable-creatures config
      val poke = Config.notableCreatures.contains(killer.toLowerCase())
      if (poke == true) {
        notablePoke = Config.notableRole
        embedColor = 4922769 // bright purple
      }

      val epochSecond = ZonedDateTime.parse(charDeath.death.time).toEpochSecond
      val embedText = s"$guildText$context at level ${charDeath.death.level.toInt} by $bossIcon**$killer**\n$context at <t:$epochSecond>"
      new EmbedBuilder()
        .setTitle(s"$charName ${vocEmoji(charDeath.char)}", charUrl(charName))
        .setDescription(embedText)
        .setThumbnail(embedThumbnail)
        .setColor(embedColor)
        .build()
    }
    // Send the embeds one at a time, otherwise some don't get sent if sending a lot at once
    embeds.foreach { embed =>
      deathsChannel.sendMessageEmbeds(embed).queue()
    }
    if (notablePoke != ""){
      deathsChannel.sendMessage(notablePoke).queue();
    }
    cleanUp()

    Future.successful()
  }.withAttributes(logAndResume)

  // Remove players from the list who haven't logged in for a while. Remove old saved deaths.
  private def cleanUp(): Unit = {
    val now = ZonedDateTime.now()
    recentOnline.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < onlineRecentDuration
    }
    recentDeaths.filterInPlace { i =>
      val diff = java.time.Duration.between(i.time, now).getSeconds
      diff < deathRecentDuration
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
      scanForDeaths via
      postToDiscordAndCleanUp to Sink.ignore

}
