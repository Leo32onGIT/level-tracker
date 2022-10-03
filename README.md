# Death Tracker

Discord bot to track when players are killed on Tibia.    

# Configuration & Details

Configure the tibia server that is tracked [here](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/scala/com/kiktibia/deathtracker/tibiadata/TibiaDataClient.scala#L20).    
You will need to point to your own discord emojis and nemesis role [here](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L6-L23) and [here](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/scala/com/kiktibia/deathtracker/DeathTrackerStream.scala#L190-L194).

  `no color` = neutral pve    
  `white` = neutral pvp    
  `red` = ally    
  `green` = hunted    
  `purple` = rare boss (this pokes)    

![examples](https://i.imgur.com/nMJK05h.gif)

It will also poke a [discord role](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L23) if someone dies to a [tracked monster](https://github.com/Leo32onGIT/death-tracker/blob/main/death-tracker/src/main/resources/application.conf#L24-L94).

![tracked boss](https://i.imgur.com/Tjofi3h.png)

# Deployment steps

Build docker image  
1. `cd death-tracker`
1. `sbt docker:publishLocal`

Copy to server  
1. `docker images`
1. `docker save <image_id> | bzip2 | ssh bots docker load`

On the server
1. Create an env file with the missing data from `src/main/resources/application.conf`
1. Run the docker container, pointing to the env file created in step 1: `docker run --rm -d --env-file prod.env --name death-tracker <image_id>`
