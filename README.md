# Team Locator – encrypted channel via HiveMQ

Positions are NOT sent through Minecraft chat. The mod encrypts them (AES-256) and sends
them through a public message broker (broker.hivemq.com, TLS) straight to your friends' mods.
Only people with the same team password can read them. The Minecraft server, its admins
and other players see nothing. The mod only connects while you are on a multiplayer server
and the mod is on (never in singleplayer, never in the main menu).
All /tl commands are client-side and are never sent to the server.

## Setup (everyone, once)
    /tl team <same-long-password>
Agree on the password via Discord/WhatsApp, NOT in Minecraft chat.

## Keys (Options > Controls > Key Binds, rebindable)
- O: share position on/off
- K: whole mod on/off (disconnects from HiveMQ immediately)

## Commands
/tl (status), /tl on, /tl off, /tl team <password>, /tl size <50-150>,
/tl interval <250-60000> (milliseconds, default 500), /tl broker <host:port>, /tl clear

## Move the HUD
/tl pos topleft | topright | bottomleft | bottomright   (pick a corner)
/tl pos <x> <y>   (distance from that corner in pixels, e.g. /tl pos 10 40)
/tl pos reset     (back to top left)
/tl pos           (show a preview)

## Privacy
HiveMQ sees the IP address of everyone connected (use a VPN to hide yours),
but cannot read the encrypted positions.

## Minecraft 26.2 version
This is the 26.2 build of Team Locator. It uses exactly the same encryption and channel
as the 1.21.11 build, so players on 26.2 and players on 1.21.11 see each other, as long as
they use the same team password and are on the same server (same server address).
Requires Fabric Loader 0.19+, Fabric API for 26.2 and Java 25 (bundled with the Minecraft launcher).
