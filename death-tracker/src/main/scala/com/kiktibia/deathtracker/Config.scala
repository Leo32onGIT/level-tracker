package com.kiktibia.deathtracker

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  private val root = ConfigFactory.load().getConfig("death-tracker")

  val token: String = root.getString("token")
  val guildId: String = root.getString("guild-id")
  val deathsChannelId: String = root.getString("deaths-channel-id")
  val creatureUrlMappings: Map[String, String] = root.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  // nemesis filter
  val notableCreatures: List[String] = root.getStringList("notable-creatures").asScala.toList
	val nemesisCreatures: List[String] = root.getStringList("nemesis-creatures").asScala.toList
	val archfoeCreatures: List[String] = root.getStringList("archfoe-creatures").asScala.toList
	val baneCreatures: List[String] = root.getStringList("bane-creatures").asScala.toList
  val notableRole: String = root.getString("notable-role")
  val nemesisEmoji: String = root.getString("nemesis-emoji")
	val archfoeEmoji: String = root.getString("archfoe-emoji")
	val baneEmoji: String = root.getString("bane-emoji")

	// hunted
	val huntedGuilds: List[String] = root.getStringList("hunted-guilds").asScala.toList
	val allyGuilds: List[String] = root.getStringList("ally-guilds").asScala.toList
	val huntedPlayers: List[String] = root.getStringList("hunted-players").asScala.toList
	val allyPlayers: List[String] = root.getStringList("ally-players").asScala.toList

}
