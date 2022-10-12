package com.kiktibia.leveltracker

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Guild

import scala.concurrent.ExecutionContextExecutor

object BotApp extends App with StrictLogging {
  logger.info("Starting up")

  implicit private val actorSystem: ActorSystem = ActorSystem()
  implicit private val ex: ExecutionContextExecutor = actorSystem.dispatcher

  private val jda = JDABuilder.createDefault(Config.token)
    .build()

  jda.awaitReady()
  logger.info("JDA ready")

  private val guild: Guild = jda.getGuildById(Config.guildId)

  private val allyChannel = guild.getTextChannelById(Config.allyChannelId)
	private val enemyChannel = guild.getTextChannelById(Config.enemyChannelId)
	private val neutralChannel = guild.getTextChannelById(Config.neutralChannelId)
  private val levelTrackerStream = new LevelTrackerStream(allyChannel, enemyChannel, neutralChannel)

  levelTrackerStream.stream.run()
}
