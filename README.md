# Team Locator 1.2 – encrypted channel via HiveMQ

Positions are NOT sent through Minecraft chat. The mod encrypts them (AES-256) and sends
them through a public message broker (broker.hivemq.com, TLS) straight to your friends' mods.
Only people with the same team password can read them. The Minecraft server, its admins
and other players see nothing. The mod only connects while you are on a multiplayer server
and the mod is on (never in singleplayer, never in the main menu).
All /tl commands are client-side and are never sent to the server.

## Setup (everyone, once)
    /tl team <same-long-password>
Agree on the password via Discord/WhatsApp, NOT in Minecraft chat.

## Head markers (waypoints)
Teammates who share their position appear as their skin face at the spot where they are,
with the distance below. If they are off-screen, the head sticks to the screen edge with an arrow.
Positions are smoothed so the heads glide instead of jumping.
The heads follow the real camera and the real field of view of each frame, so freelook, freecam,
third person, zoom mods, spyglass and the sprint FOV effect all work.
While you can actually see a teammate (on screen, no blocks in between), their head fades out.

## Settings
Open with key J, /tl config, or Mod Menu -> Team Locator -> Configure (if Mod Menu is installed).
Everything can be toggled there: head markers, distance, name, edge markers, head size,
hide distance, max distance, hide when visible, team list, list corner/size, update speed.

## Keys (Options > Controls > Key Binds, rebindable)
- O: share position on/off
- K: whole mod on/off
- J: open settings (disconnects from HiveMQ immediately)

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
