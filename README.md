# Death Tracker

Discord bot to track when players are killed on Seanera.    

![in a guild](https://i.imgur.com/6KAvSjh.png)   
![neutral](https://i.imgur.com/FEymk3w.png)

It will also poke a [discord role](https://github.com/Leo32onGIT/death-tracker/blob/seanera/death-tracker/src/main/resources/application.conf#L5) if someone dies to a [tracked monster](https://github.com/Leo32onGIT/death-tracker/blob/seanera/death-tracker/src/main/resources/application.conf#L7).

![tracked boss](https://i.imgur.com/cXF8r8j.png)

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
