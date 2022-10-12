package com.kiktibia.leveltracker

import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._

object Config {
  private val root = ConfigFactory.load().getConfig("level-tracker")

  val token: String = root.getString("token")
  val guildId: String = root.getString("guild-id")
  val allyChannelId: String = root.getString("ally-channel-id")
	val enemyChannelId: String = root.getString("enemy-channel-id")
	val neutralChannelId: String = root.getString("neutral-channel-id")
  val creatureUrlMappings: Map[String, String] = root.getObject("creature-url-mappings").asScala.map {
    case (k, v) => k -> v.unwrapped().toString
  }.toMap

  val enemyGuilds: List[String] = root.getStringList("enemy-guilds").asScala.toList
  val allyGuilds: List[String] = root.getStringList("ally-guilds").asScala.toList
  val enemyPlayers: List[String] = root.getStringList("enemy-players").asScala.toList
  val allyPlayers: List[String] = root.getStringList("ally-players").asScala.toList
  val allyGuild: String = root.getString("allyguild-emoji")
  val otherGuild: String = root.getString("otherguild-emoji")
}
