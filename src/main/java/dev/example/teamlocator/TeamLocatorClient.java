package dev.example.teamlocator;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Team Locator – encrypted channel via HiveMQ
 *
 * Positions are NOT sent through Minecraft chat. They are encrypted (AES-256-GCM) and sent
 * through a public MQTT broker (default: broker.hivemq.com, TLS) straight to your friends' mods.
 * Only people with the same team password can read them. The Minecraft server, its admins
 * and other players see nothing. The mod only connects while you are on a multiplayer server
 * (never in singleplayer) and while the mod is turned on.
 *
 * All /tl commands are client-side and are never sent to the server.
 */
public class TeamLocatorClient implements ClientModInitializer {

    /** Real world FOV of the current frame (set by GameRendererMixin). -1 = unknown. */
    public static volatile float renderFov = -1f;

    private static int setCorner(CommandContext<FabricClientCommandSource> ctx, String corner) {
        hudCorner = corner;
        hudOffsetX = 4;
        hudOffsetY = 4;
        showPreview(ctx);
        return 1;
    }

    private static void showPreview(CommandContext<FabricClientCommandSource> ctx) {
        saveConfig();
        previewUntilMs = System.currentTimeMillis() + 6000;
        feedback(ctx, "HUD position: " + hudCorner + ", " + hudOffsetX + " " + hudOffsetY
                + " (distance from that corner). Change with /tl pos <x> <y>");
    }

    // ------------------------------------------------------------------ Config
    private static final Path CONFIG =
            FabricLoader.getInstance().getConfigDir().resolve("team-locator.properties");

    private static boolean enabled = true;
    private static String password = "";
    private static String broker = "broker.hivemq.com:8883";
    /** How often to send while moving, in milliseconds (min. 250). */
    private static int intervalMs = 500;
    private static float hudScale = 0.75f;
    /** HUD position: corner (topleft, topright, bottomleft, bottomright) + distance from that corner in pixels. */
    private static String hudCorner = "topleft";
    private static int hudOffsetX = 4;
    private static int hudOffsetY = 4;
    // Head markers (waypoints)
    private static boolean showHeads = true;       // player heads in the world direction
    private static boolean showHeadDistance = true;
    private static boolean showHeadName = false;
    private static boolean edgeMarkers = true;     // keep off-screen heads at the screen edge
    private static boolean showList = true;        // the small team list box
    private static int headSize = 12;              // pixels
    private static int hideCloser = 4;             // hide head when closer than this (blocks)
    private static int maxHeadDistance = 0;        // 0 = unlimited
    private static boolean hideWhenVisible = true; // fade the head out while you can actually see the player
    private static boolean openConfigNextTick = false;

    /** Show the HUD as a preview until this time (after moving it). */
    private static long previewUntilMs = 0;

    private static final int EXPIRE_SECONDS = 60;      // entry disappears without updates
    private static final int STALE_SECONDS = 15;       // shown grey after this
    private static final int FORCE_SEND_SECONDS = 5;   // send at least this often, even when standing still
    private static final double MIN_MOVE = 1.0;        // blocks moved before sending again
    private static final long MAX_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L;

    // ------------------------------------------------------------------- State
    private record Mark(String name, String dimension, double x, double y, double z, long timeMs) {}

    private static final Map<String, Mark> MARKS = new ConcurrentHashMap<>();
    /** Smoothed display positions for heads: x, y, z, lastNanos. Avoids jumping between updates. */
    private static final Map<String, double[]> SMOOTH = new ConcurrentHashMap<>();
    /** Per player: current alpha, hidden-by-aim flag (0/1). Gives smooth fading without flicker. */
    private static final Map<String, float[]> FADE = new ConcurrentHashMap<>();
    /** Per player: lastCheckNs, visibleSinceNs (0 = not visible), lastVisibleNs. */
    private static final Map<String, long[]> VIS = new ConcurrentHashMap<>();
    private static final long VIS_CHECK_NS = 80_000_000L;   // line-of-sight check every 80 ms
    private static final long VIS_HIDE_NS = 150_000_000L;   // must be visible 150 ms before hiding
    private static final long VIS_SHOW_NS = 300_000_000L;   // must be hidden 300 ms before showing again
    private static long lastHeadFrameNs = 0;
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private static KeyMapping toggleShareKey;
    private static KeyMapping toggleAllKey;
    private static KeyMapping configKey;

    private static boolean sharing = false;
    private static int tickCounter = 0;
    private static long lastSendMs = 0;
    private static double lastX, lastY, lastZ;
    private static String lastDim = "";

    private static volatile TeamCrypto crypto;
    private static MqttLink link;
    private static volatile String myName = "";
    private static volatile String myServer = "";

    // Diagnostics for /tl debug
    private static volatile int statReceived = 0;      // messages that arrived on our channel
    private static volatile int statShown = 0;         // positions accepted and shown
    private static volatile int statWrongServer = 0;   // dropped: friend is on a different server
    private static volatile int statOldClock = 0;      // dropped: timestamp too far off
    private static volatile int statBadData = 0;       // dropped: could not decrypt / read
    private static volatile int statSent = 0;          // own positions sent
    private static volatile String lastOtherServer = "";
    private static volatile int reconnects = 0;

    @Override
    public void onInitializeClient() {
        loadConfig();
        crypto = password.isEmpty() ? null : TeamCrypto.create(password);

        KeyMapping.Category cat = KeyMapping.Category.MISC; // before 1.21.9: "key.categories.misc"
        toggleShareKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.team_locator.toggle_share", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, cat));
        toggleAllKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.team_locator.toggle_all", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, cat));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.team_locator.config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(TeamLocatorClient::tick);

        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            if (sharing) sendBye();
            sharing = false;
            MARKS.clear();
            stopLink();
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("team_locator", "hud"),
                (g, delta) -> {
                    Minecraft mc = Minecraft.getInstance();
                    try {
                        renderHeads(mc, g, delta);
                    } catch (Exception e) {
                        // never crash the game because of a marker
                    }
                    if (showList || System.currentTimeMillis() < previewUntilMs) render(mc, g);
                });

        registerCommands();
    }

    // ------------------------------------------------------------------ Tick

    private static void tick(Minecraft mc) {
        boolean inGame = mc.player != null && mc.level != null && mc.getConnection() != null;
        boolean onServer = inGame && mc.getCurrentServer() != null && !mc.isLocalServer();
        if (inGame) {
            myName = mc.player.getGameProfile().name();
            myServer = onServer ? normalizeServer(mc.getCurrentServer().ip) : "";
        }

        // Only connect to the broker when needed: mod on, password set, on a multiplayer server
        boolean wantLink = enabled && onServer && crypto != null;
        if (wantLink && link == null) startLink();
        if (!wantLink && link != null) stopLink();

        while (toggleAllKey.consumeClick()) setEnabled(mc, !enabled);
        while (configKey.consumeClick()) openConfigNextTick = true;
        if (openConfigNextTick) {
            openConfigNextTick = false;
            mc.gui.setScreen(new ConfigScreen(mc.gui.screen()));
        }

        while (toggleShareKey.consumeClick()) {
            if (!enabled) {
                actionbar(mc, "§cTeam Locator is off (" + keyName(toggleAllKey) + ")");
                continue;
            }
            if (crypto == null) {
                info(mc, "Set a team password first: /tl team <password>");
                continue;
            }
            if (!onServer) {
                actionbar(mc, "§cOnly works on multiplayer servers");
                continue;
            }
            sharing = !sharing;
            if (sharing) {
                lastSendMs = 0; // send immediately
            } else {
                sendBye();
            }
            actionbar(mc, sharing ? "§aSharing position: ON" : "§cSharing position: OFF");
        }

        long now = System.currentTimeMillis();
        MARKS.values().removeIf(m -> now - m.timeMs() > EXPIRE_SECONDS * 1000L);
        SMOOTH.keySet().retainAll(MARKS.keySet());
        FADE.keySet().retainAll(MARKS.keySet());
        VIS.keySet().retainAll(MARKS.keySet());

        if (!enabled || !sharing || !onServer || link == null) return;
        if (lastSendMs != 0 && ++tickCounter < Math.max(5, intervalMs / 50)) return;
        tickCounter = 0;

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        String dim = currentDimension(mc);
        double moved = Math.sqrt((x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ));
        boolean due = lastSendMs == 0 || moved >= MIN_MOVE || !dim.equals(lastDim)
                || now - lastSendMs >= FORCE_SEND_SECONDS * 1000L;
        if (!due) return;

        publish(String.join("|", "v1", "pos", myName, myServer, dim,
                Integer.toString((int) Math.floor(x)), Integer.toString((int) Math.floor(y)),
                Integer.toString((int) Math.floor(z)), Long.toString(now)));
        lastSendMs = now;
        statSent++;
        lastX = x; lastY = y; lastZ = z; lastDim = dim;
    }

    /**
     * Makes different spellings of the same server match:
     * "Play.LifeSteal.net:25565", "eu.lifesteal.net" and "lifesteal.net" all become "lifesteal.net".
     */
    static String normalizeServer(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.startsWith("[")) return a;                       // IPv6 literal, keep as is
        int colon = a.lastIndexOf(':');
        if (colon > 0) a = a.substring(0, colon);              // remove port
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        if (a.matches("[0-9.]+")) return a;                    // IPv4 address, keep as is
        String[] parts = a.split("\\.");
        if (parts.length <= 2) return a;
        String last = parts[parts.length - 1], second = parts[parts.length - 2];
        // keep three labels for things like example.co.uk
        if (last.length() == 2 && second.length() <= 3 && parts.length >= 3) {
            return parts[parts.length - 3] + "." + second + "." + last;
        }
        return second + "." + last;
    }

    private static void sendBye() {
        publish(String.join("|", "v1", "bye", myName, myServer, Long.toString(System.currentTimeMillis())));
    }

    private static void publish(String plain) {
        TeamCrypto c = crypto;
        MqttLink l = link;
        if (c == null || l == null) return;
        byte[] data = c.encrypt(plain);
        if (data != null) l.publish(data);
    }

    // ------------------------------------------------------------------ Receiving (network thread)

    private static void onMessage(byte[] data) {
        TeamCrypto c = crypto;
        if (c == null || !enabled) return;
        statReceived++;
        String plain = c.decrypt(data);
        if (plain == null) { statBadData++; return; } // wrong password or foreign data -> ignore

        String[] p = plain.split("\\|");
        if (p.length < 5 || !p[0].equals("v1")) { statBadData++; return; }
        String type = p[1], name = p[2], server = p[3];
        if (name.equalsIgnoreCase(myName)) return;            // own message
        if (myServer.isEmpty() || !normalizeServer(server).equals(myServer)) { // different Minecraft server
            statWrongServer++;
            lastOtherServer = name + " @ " + server;
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);

        try {
            if (type.equals("bye")) {
                MARKS.remove(key);
            } else if (type.equals("pos") && p.length >= 9) {
                long sent = Long.parseLong(p[8]);
                if (Math.abs(System.currentTimeMillis() - sent) > MAX_CLOCK_SKEW_MS) { statOldClock++; return; }
                MARKS.put(key, new Mark(name, p[4],
                        Integer.parseInt(p[5]) + 0.5, Integer.parseInt(p[6]), Integer.parseInt(p[7]) + 0.5,
                        System.currentTimeMillis()));
                statShown++;
            }
        } catch (NumberFormatException ignored) {
            statBadData++;
        }
    }

    // ------------------------------------------------------------------ Connection

    private static void startLink() {
        TeamCrypto c = crypto;
        if (c == null) return;
        String host = broker;
        int port = 8883;
        int i = broker.lastIndexOf(':');
        if (i > 0) {
            host = broker.substring(0, i);
            try { port = Integer.parseInt(broker.substring(i + 1)); } catch (NumberFormatException ignored) { }
        }
        link = new MqttLink(host, port, c.topic, TeamLocatorClient::onMessage);
        link.start();
    }

    private static void stopLink() {
        if (link != null) {
            link.stop();
            link = null;
        }
    }

    private static void setEnabled(Minecraft mc, boolean on) {
        if (!on && sharing) sendBye();
        enabled = on;
        if (!on) {
            sharing = false;
            MARKS.clear();
            stopLink();
        }
        saveConfig();
        actionbar(mc, on ? "§aTeam Locator: ON" : "§cTeam Locator: OFF");
    }

    // ------------------------------------------------------------------ HUD

    private static void render(Minecraft mc, GuiGraphicsExtractor g) {
        if (mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;
        boolean preview = System.currentTimeMillis() < previewUntilMs;
        if (!preview) {
            if (!enabled) return;
            if (MARKS.isEmpty() && !sharing) return;
        }

        String myDim = currentDimension(mc);
        long now = System.currentTimeMillis();
        double px = mc.player.getX(), pz = mc.player.getZ();

        record Line(String text, int color, double dist) {}
        List<Line> lines = new ArrayList<>();
        for (Mark mk : MARKS.values()) {
            long age = (now - mk.timeMs()) / 1000;
            boolean stale = age > STALE_SECONDS;
            if (!mk.dimension().equals(myDim)) {
                lines.add(new Line(mk.name() + " §7(" + dimName(mk.dimension()) + ")", 0xFFFFD080, Double.MAX_VALUE));
                continue;
            }
            double dx = mk.x() - px, dz = mk.z() - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double rel = ((targetYaw - mc.player.getYRot()) % 360 + 540) % 360 - 180;
            int idx = (int) Math.floorMod(Math.round(rel / 45.0), 8L);
            lines.add(new Line(mk.name() + " " + ARROWS[idx] + " " + Math.round(dist) + " blocks"
                    + (stale ? " §8(old)" : ""), stale ? 0xFFAAAAAA : 0xFFFFFFFF, dist));
        }
        lines.sort(Comparator.comparingDouble(Line::dist));

        boolean online = link != null && link.isConnected();
        String header = "Team" + (sharing ? " §a●" : "") + (online ? "" : " §c(offline)");
        if (preview && lines.isEmpty()) {
            lines.add(new Line("Steve ↗ 245 blocks", 0xFFFFFFFF, 0));
            lines.add(new Line("Alex → 1203 blocks", 0xFFFFFFFF, 0));
            header = "Team §e(preview)";
        }

        int pad = 3, lineH = 10;
        int w = mc.font.width(header);
        for (Line l : lines) w = Math.max(w, mc.font.width(l.text()));
        int h = lineH * (1 + lines.size());

        // Box size on screen (after scaling) -> position from the chosen corner
        int boxW = Math.round((w + pad * 2) * hudScale);
        int boxH = Math.round((h + pad * 2 - 2) * hudScale);
        boolean right = hudCorner.endsWith("right");
        boolean bottom = hudCorner.startsWith("bottom");
        int sx = right ? g.guiWidth() - boxW - hudOffsetX : hudOffsetX;
        int sy = bottom ? g.guiHeight() - boxH - hudOffsetY : hudOffsetY;
        sx = Math.max(0, Math.min(g.guiWidth() - boxW, sx));
        sy = Math.max(0, Math.min(g.guiHeight() - boxH, sy));

        g.pose().pushMatrix();
        g.pose().translate(sx, sy);
        g.pose().scale(hudScale, hudScale);
        g.fill(0, 0, w + pad * 2, h + pad * 2 - 2, 0x66000000);
        g.text(mc.font, header, pad, pad, 0xFF80FFFF, true);
        int y = pad + lineH;
        for (Line l : lines) {
            g.text(mc.font, l.text(), pad, y, l.color(), true);
            y += lineH;
        }
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ Head markers (waypoints)

    private static final String[] EDGE_ARROWS = {"→", "↘", "↓", "↙", "←", "↖", "↑", "↗"};

    private static void renderHeads(Minecraft mc, GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!enabled || !showHeads || mc.player == null || mc.level == null || mc.gui.hud.isHidden()) return;
        if (MARKS.isEmpty()) return;

        float pt = delta.getGameTimeDeltaPartialTick(true);

        // Use the REAL camera (position + rotation). Freelook, freecam, third person and
        // camera mods all change the camera, so the heads always stay on the right spot.
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 camPos = cam.position();
        Quaternionf toCamera = new Quaternionf(cam.rotation()).conjugate();

        // Real FOV of this frame (zoom, sprint effect, spyglass ...). Fallback: options value.
        float rf = renderFov;
        double fovDeg = (rf > 1f && rf < 179f) ? rf : mc.options.fov().get();
        double fov = Math.toRadians(fovDeg);
        int w = g.guiWidth(), h = g.guiHeight();
        double scale = (h / 2.0) / Math.tan(fov / 2.0);
        double cx = w / 2.0, cy = h / 2.0;
        String myDim = currentDimension(mc);
        long nowNs = System.nanoTime();
        long now = System.currentTimeMillis();
        double frameDt = lastHeadFrameNs == 0 ? 0.016 : Math.min(0.25, (nowNs - lastHeadFrameNs) / 1e9);
        lastHeadFrameNs = nowNs;
        int size = headSize;
        int margin = size / 2 + 12;

        for (Mark mk : MARKS.values()) {
            if (!mk.dimension().equals(myDim)) continue;
            String key = mk.name().toLowerCase(Locale.ROOT);

            // Position: real entity if loaded (perfectly smooth), otherwise smoothed network position
            // Target: the real entity if it is loaded (exact, interpolated every frame),
            // otherwise the last position received from the network.
            Player ent = findLoaded(mc, mk.name());
            double gx, gy, gz, rate;
            if (ent != null) {
                Vec3 p = ent.getPosition(pt);
                gx = p.x; gy = p.y; gz = p.z;
                rate = 30.0;   // follows the entity almost exactly
            } else {
                gx = mk.x(); gy = mk.y(); gz = mk.z();
                rate = 6.0;    // glides between network updates
            }
            // One smoothed position for both cases -> no jump when a player gets loaded/unloaded
            double[] sm = SMOOTH.get(key);
            double jump = sm == null ? Double.MAX_VALUE
                    : Math.abs(sm[0] - gx) + Math.abs(sm[1] - gy) + Math.abs(sm[2] - gz);
            if (sm == null || jump > 64) { // first time, teleport or dimension change -> snap
                sm = new double[]{gx, gy, gz, nowNs};
                SMOOTH.put(key, sm);
            }
            double sdt = Math.min(0.25, (nowNs - sm[3]) / 1e9);
            sm[3] = nowNs;
            double a = 1 - Math.exp(-sdt * rate);
            sm[0] += (gx - sm[0]) * a;
            sm[1] += (gy - sm[1]) * a;
            sm[2] += (gz - sm[2]) * a;
            double tx = sm[0], ty = sm[1] + 2.3, tz = sm[2];

            // Distance is measured from YOUR player (not the free camera), like in normal play
            Vec3 me = mc.player.getPosition(pt);
            double mdx = tx - me.x, mdz = tz - me.z;
            double dist = Math.sqrt(mdx * mdx + mdz * mdz);
            if (dist < hideCloser) continue;
            if (maxHeadDistance > 0 && dist > maxHeadDistance) continue;

            // World -> camera space with the camera's own rotation (handles yaw, pitch and roll)
            Vector3f v = new Vector3f((float) (tx - camPos.x), (float) (ty - camPos.y), (float) (tz - camPos.z));
            toCamera.transform(v);
            double right = v.x(), up = v.y(), depth = -v.z();

            double sx = cx, sy = cy;
            boolean onScreen = false;
            if (depth > 0.05) {
                sx = cx + right / depth * scale;
                sy = cy - up / depth * scale;
                onScreen = sx >= margin && sx <= w - margin && sy >= margin && sy <= h - margin;
            }
            String arrow = "";
            if (!onScreen) {
                if (!edgeMarkers) {
                    FADE.remove(key);
                    continue;
                }
                // Direction on the screen plane; stable both in front and behind
                double vx = right, vy = -up;
                double len = Math.hypot(vx, vy);
                if (len < 1e-6) { vx = 0; vy = 1; len = 1; }
                vx /= len; vy /= len;
                double hx = w / 2.0 - margin, hy = h / 2.0 - margin;
                double t = Math.min(Math.abs(vx) > 1e-6 ? hx / Math.abs(vx) : Double.MAX_VALUE,
                        Math.abs(vy) > 1e-6 ? hy / Math.abs(vy) : Double.MAX_VALUE);
                sx = cx + vx * t;
                sy = cy + vy * t;
                double ang = Math.atan2(vy, vx); // 0 = right, positive = down
                int idx = (int) Math.floorMod(Math.round(ang / (Math.PI / 4)), 8L);
                arrow = EDGE_ARROWS[idx] + " ";
            }

            // Fade out while you can really see the player: on screen AND no blocks in between.
            // Checked every 80 ms with a delay in both directions -> no flicker.
            float[] fade = FADE.computeIfAbsent(key, k -> new float[]{0f, 0f});
            if (hideWhenVisible && ent != null) {
                long[] vis = VIS.computeIfAbsent(key, k -> new long[]{0L, 0L, 0L});
                if (nowNs - vis[0] > VIS_CHECK_NS) {
                    vis[0] = nowNs;
                    boolean seen = canSee(mc, ent, pt, camPos, toCamera, scale, cx, cy, w, h);
                    if (seen) {
                        if (vis[1] == 0L) vis[1] = nowNs;
                        vis[2] = nowNs;
                    } else {
                        vis[1] = 0L;
                    }
                }
                if (vis[1] != 0L && nowNs - vis[1] >= VIS_HIDE_NS) fade[1] = 1f;   // seen long enough -> hide
                else if (nowNs - vis[2] >= VIS_SHOW_NS) fade[1] = 0f;              // hidden long enough -> show
            } else {
                // Not loaded (far away) or option off -> you can't see them, keep the marker
                fade[1] = 0f;
                VIS.remove(key);
            }
            float target = fade[1] == 1f ? 0f : 1f;
            fade[0] += (float) ((target - fade[0]) * (1 - Math.exp(-frameDt * 12.0)));
            float alpha = fade[0];
            if (alpha < 0.04f) continue;

            boolean stale = (now - mk.timeMs()) / 1000 > STALE_SECONDS && ent == null;
            int tint = withAlpha(stale ? 0x888888 : 0xFFFFFF, alpha);

            // Draw around (sx, sy) with sub-pixel precision -> no 1-pixel jitter while turning
            g.pose().pushMatrix();
            g.pose().translate((float) sx, (float) sy);
            int x = -size / 2;
            int y = -size / 2;

            // Head with a thin dark border
            g.fill(x - 1, y - 1, x + size + 1, y + size + 1, withAlpha(0x000000, alpha * 0.75f));
            if (!drawFace(mc, g, ent, mk.name(), x, y, size, tint)) {
                g.fill(x, y, x + size, y + size, withAlpha(stale ? 0x555555 : 0x3A6EA5, alpha));
                String initial = mk.name().substring(0, 1).toUpperCase(Locale.ROOT);
                g.centeredText(mc.font, initial, 0, y + (size - 8) / 2, withAlpha(0xFFFFFF, alpha));
            }

            // Small labels (scaled down so they don't get in the way)
            float ts = 0.75f;
            if (showHeadName) {
                drawSmallCentered(mc, g, mk.name(), 0, y - 8, ts, withAlpha(0xFFFFFF, alpha), alpha);
            }
            if (showHeadDistance) {
                drawSmallCentered(mc, g, arrow + Math.round(dist) + "m", 0, y + size + 3, ts,
                        withAlpha(stale ? 0xAAAAAA : 0xFFFFFF, alpha), alpha);
            }
            g.pose().popMatrix();
        }
    }

    /**
     * True if the player is inside your view and at least one point of their body (head, chest
     * or feet) can be reached from the camera without hitting a block.
     */
    private static boolean canSee(Minecraft mc, Player ent, float pt, Vec3 camPos, Quaternionf toCamera,
                                  double scale, double cx, double cy, int w, int h) {
        if (ent.isInvisible()) return false;
        Vec3 base = ent.getPosition(pt);
        double height = ent.getBbHeight();
        double[] offsets = {height * 0.9, height * 0.5, 0.15};
        for (double off : offsets) {
            Vec3 point = base.add(0, off, 0);
            // 1) inside the visible screen area?
            Vector3f v = new Vector3f((float) (point.x - camPos.x), (float) (point.y - camPos.y),
                    (float) (point.z - camPos.z));
            toCamera.transform(v);
            double depth = -v.z();
            if (depth <= 0.05) continue;
            double px = cx + v.x() / depth * scale;
            double py = cy - v.y() / depth * scale;
            if (px < 0 || px > w || py < 0 || py > h) continue;
            // 2) nothing solid in between?
            HitResult hit = mc.level.clip(new ClipContext(camPos, point, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    private static int withAlpha(int rgb, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static void drawSmallCentered(Minecraft mc, GuiGraphicsExtractor g, String text, int cx, int y, float s, int color,
                                          float alpha) {
        int tw = mc.font.width(text);
        g.pose().pushMatrix();
        g.pose().translate(cx, y);
        g.pose().scale(s, s);
        g.fill(-tw / 2 - 2, -1, tw / 2 + 2, 9, withAlpha(0x000000, alpha * 0.5f));
        g.text(mc.font, text, -tw / 2, 0, color, true);
        g.pose().popMatrix();
    }

    /** Draws the player's face from their skin. Returns false if no skin is known yet. */
    private static boolean drawFace(Minecraft mc, GuiGraphicsExtractor g, Player ent, String name, int x, int y, int size, int tint) {
        if (ent instanceof AbstractClientPlayer acp) {
            PlayerFaceExtractor.extractRenderState(g, acp.getSkin(), x, y, size, tint);
            return true;
        }
        if (mc.getConnection() != null) {
            var info = mc.getConnection().getPlayerInfo(name);
            if (info != null) {
                PlayerFaceExtractor.extractRenderState(g, info.getSkin(), x, y, size, tint);
                return true;
            }
        }
        return false;
    }

    private static Player findLoaded(Minecraft mc, String name) {
        if (mc.level == null) return null;
        for (Player p : mc.level.players()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static String currentDimension(Minecraft mc) {
        return mc.level.dimension().identifier().toString(); // older mappings: .location()
    }

    private static String dimName(String dim) {
        return switch (dim) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> dim.substring(dim.indexOf(':') + 1);
        };
    }

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    private static void info(Minecraft mc, String text) {
        // 26.2: hints are shown above the hotbar
        mc.gui.hud.setOverlayMessage(Component.literal("§b[Team Locator]§r " + text), false);
    }

    private static void actionbar(Minecraft mc, String text) {
        mc.gui.hud.setOverlayMessage(Component.literal(text), false);
    }

    private static void feedback(CommandContext<FabricClientCommandSource> ctx, String text) {
        ctx.getSource().sendFeedback(Component.literal("§b[Team Locator]§r " + text));
    }

    // ------------------------------------------------------------------ Commands (client-side only)

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((d, access) -> d.register(
                ClientCommands.literal("tl")
                        .executes(ctx -> {
                            boolean online = link != null && link.isConnected();
                            feedback(ctx, (enabled ? "§aON" : "§cOFF")
                                    + "§r | Password: " + (crypto != null ? "set" : "§cmissing§r")
                                    + " | Channel: " + (online ? "§aconnected§r" : "§cdisconnected§r")
                                    + " | Sharing: " + (sharing ? "on" : "off")
                                    + " | HUD: " + hudCorner + " " + hudOffsetX + " " + hudOffsetY
                                    + " | Keys: " + keyName(toggleShareKey) + " = share, "
                                    + keyName(toggleAllKey) + " = mod on/off");
                            return 1;
                        })
                        .then(ClientCommands.literal("on").executes(ctx -> {
                            setEnabled(Minecraft.getInstance(), true);
                            return 1;
                        }))
                        .then(ClientCommands.literal("off").executes(ctx -> {
                            setEnabled(Minecraft.getInstance(), false);
                            return 1;
                        }))
                        .then(ClientCommands.literal("team")
                                .then(ClientCommands.argument("password", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String pw = StringArgumentType.getString(ctx, "password").trim();
                                            if (pw.length() < 8) {
                                                feedback(ctx, "§cPassword too short (at least 8 characters, 15+ is better).");
                                                return 0;
                                            }
                                            if (sharing) sendBye();
                                            sharing = false;
                                            stopLink();
                                            MARKS.clear();
                                            password = pw;
                                            crypto = TeamCrypto.create(pw);
                                            saveConfig();
                                            feedback(ctx, "Team password set. Everyone in your team needs exactly the same one.");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("interval")
                                .then(ClientCommands.argument("milliseconds", IntegerArgumentType.integer(250, 60000))
                                        .executes(ctx -> {
                                            intervalMs = IntegerArgumentType.getInteger(ctx, "milliseconds");
                                            saveConfig();
                                            feedback(ctx, "Interval: " + intervalMs + " ms");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("size")
                                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(50, 150))
                                        .executes(ctx -> {
                                            hudScale = IntegerArgumentType.getInteger(ctx, "percent") / 100f;
                                            saveConfig();
                                            previewUntilMs = System.currentTimeMillis() + 6000;
                                            feedback(ctx, "HUD size: " + Math.round(hudScale * 100) + "%");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("pos")
                                .then(ClientCommands.literal("topleft").executes(ctx -> setCorner(ctx, "topleft")))
                                .then(ClientCommands.literal("topright").executes(ctx -> setCorner(ctx, "topright")))
                                .then(ClientCommands.literal("bottomleft").executes(ctx -> setCorner(ctx, "bottomleft")))
                                .then(ClientCommands.literal("bottomright").executes(ctx -> setCorner(ctx, "bottomright")))
                                .then(ClientCommands.literal("reset").executes(ctx -> {
                                    hudCorner = "topleft";
                                    hudOffsetX = 4;
                                    hudOffsetY = 4;
                                    showPreview(ctx);
                                    return 1;
                                }))
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 2000))
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 2000))
                                                .executes(ctx -> {
                                                    hudOffsetX = IntegerArgumentType.getInteger(ctx, "x");
                                                    hudOffsetY = IntegerArgumentType.getInteger(ctx, "y");
                                                    showPreview(ctx);
                                                    return 1;
                                                })))
                                .executes(ctx -> {
                                    showPreview(ctx);
                                    return 1;
                                }))
                        .then(ClientCommands.literal("broker")
                                .then(ClientCommands.argument("hostport", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            broker = StringArgumentType.getString(ctx, "hostport").trim();
                                            saveConfig();
                                            stopLink();
                                            feedback(ctx, "Broker: " + broker);
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("config").executes(ctx -> {
                            openConfigNextTick = true; // open after the chat screen has closed
                            return 1;
                        }))
                        .then(ClientCommands.literal("debug").executes(ctx -> {
                            boolean online = link != null && link.isConnected();
                            feedback(ctx, "Server key: " + (myServer.isEmpty() ? "§c(not on a server)§r" : myServer)
                                    + " | Channel: " + (online ? "§aconnected§r" : "§cdisconnected§r")
                                    + " | Reconnects: " + reconnects);
                            feedback(ctx, "Sent: " + statSent + " | Received: " + statReceived
                                    + " | Shown: " + statShown + " | Other server: " + statWrongServer
                                    + " | Clock off: " + statOldClock + " | Unreadable: " + statBadData);
                            if (!lastOtherServer.isEmpty()) {
                                feedback(ctx, "Last player on another server: " + lastOtherServer);
                            }
                            return 1;
                        }))
                        .then(ClientCommands.literal("clear").executes(ctx -> {
                            MARKS.clear();
                            feedback(ctx, "HUD cleared.");
                            return 1;
                        }))));
    }

    // ------------------------------------------------------------------ Config

    private static void loadConfig() {
        Properties p = new Properties();
        if (Files.exists(CONFIG)) {
            try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.err.println("[Team Locator] Could not read config: " + e.getMessage());
            }
        }
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "" + enabled));
        password = p.getProperty("password", password);
        broker = p.getProperty("broker", broker);
        try {
            intervalMs = Math.max(250, Integer.parseInt(p.getProperty("intervalMs", "" + intervalMs)));
        } catch (NumberFormatException ignored) { }
        try {
            hudScale = Math.max(0.5f, Math.min(1.5f, Float.parseFloat(p.getProperty("hudScale", "" + hudScale))));
        } catch (NumberFormatException ignored) { }
        showHeads = Boolean.parseBoolean(p.getProperty("showHeads", "" + showHeads));
        showHeadDistance = Boolean.parseBoolean(p.getProperty("showHeadDistance", "" + showHeadDistance));
        showHeadName = Boolean.parseBoolean(p.getProperty("showHeadName", "" + showHeadName));
        edgeMarkers = Boolean.parseBoolean(p.getProperty("edgeMarkers", "" + edgeMarkers));
        showList = Boolean.parseBoolean(p.getProperty("showList", "" + showList));
        hideWhenVisible = Boolean.parseBoolean(p.getProperty("hideWhenVisible", "" + hideWhenVisible));
        try {
            headSize = Math.max(8, Math.min(32, Integer.parseInt(p.getProperty("headSize", "" + headSize))));
            hideCloser = Math.max(0, Integer.parseInt(p.getProperty("hideCloser", "" + hideCloser)));
            maxHeadDistance = Math.max(0, Integer.parseInt(p.getProperty("maxHeadDistance", "" + maxHeadDistance)));
        } catch (NumberFormatException ignored) { }
        String corner = p.getProperty("hudCorner", hudCorner);
        if (List.of("topleft", "topright", "bottomleft", "bottomright").contains(corner)) hudCorner = corner;
        try {
            hudOffsetX = Math.max(0, Integer.parseInt(p.getProperty("hudOffsetX", "" + hudOffsetX)));
            hudOffsetY = Math.max(0, Integer.parseInt(p.getProperty("hudOffsetY", "" + hudOffsetY)));
        } catch (NumberFormatException ignored) { }
        saveConfig();
    }

    private static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("enabled", "" + enabled);
        p.setProperty("password", password);
        p.setProperty("broker", broker);
        p.setProperty("intervalMs", "" + intervalMs);
        p.setProperty("hudScale", "" + hudScale);
        p.setProperty("hudCorner", hudCorner);
        p.setProperty("showHeads", "" + showHeads);
        p.setProperty("showHeadDistance", "" + showHeadDistance);
        p.setProperty("showHeadName", "" + showHeadName);
        p.setProperty("edgeMarkers", "" + edgeMarkers);
        p.setProperty("showList", "" + showList);
        p.setProperty("headSize", "" + headSize);
        p.setProperty("hideCloser", "" + hideCloser);
        p.setProperty("maxHeadDistance", "" + maxHeadDistance);
        p.setProperty("hideWhenVisible", "" + hideWhenVisible);
        p.setProperty("hudOffsetX", "" + hudOffsetX);
        p.setProperty("hudOffsetY", "" + hudOffsetY);
        try {
            Files.createDirectories(CONFIG.getParent());
            try (Writer w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                p.store(w, "Team Locator");
            }
        } catch (IOException e) {
            System.err.println("[Team Locator] Could not save config: " + e.getMessage());
        }
    }

    // ======================================================================
    // Settings screen (key J, /tl config, or Mod Menu -> Team Locator -> Configure)
    // ======================================================================
    static Screen createConfigScreen(Screen parent) {
        return new ConfigScreen(parent);
    }

    static final class ConfigScreen extends Screen {
        private static final int[] SIZES = {8, 10, 12, 14, 16, 20, 24, 32};
        private static final int[] HIDE = {0, 2, 4, 8, 16, 32};
        private static final int[] MAXD = {0, 100, 250, 500, 1000, 2500, 5000};
        private static final int[] SPEED = {250, 500, 1000, 2000, 5000};
        private static final int[] SCALE = {50, 60, 75, 90, 100, 125, 150};
        private static final String[] CORNERS = {"topleft", "topright", "bottomleft", "bottomright"};

        private final Screen parent;

        ConfigScreen(Screen parent) {
            super(Component.literal("Team Locator Settings"));
            this.parent = parent;
        }

        private static String onOff(boolean b) {
            return b ? "§aON" : "§cOFF";
        }

        private static int next(int[] values, int current) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] == current) return values[(i + 1) % values.length];
            }
            return values[0];
        }

        private Button toggle(int x, int y, int w, String label, java.util.function.BooleanSupplier get,
                              Runnable flip) {
            return Button.builder(Component.literal(label + ": " + onOff(get.getAsBoolean())), b -> {
                flip.run();
                saveConfig();
                b.setMessage(Component.literal(label + ": " + onOff(get.getAsBoolean())));
            }).bounds(x, y, w, 20).build();
        }

        private Button cycle(int x, int y, int w, String label, java.util.function.Supplier<String> value,
                             Runnable step) {
            return Button.builder(Component.literal(label + ": " + value.get()), b -> {
                step.run();
                saveConfig();
                previewUntilMs = System.currentTimeMillis() + 4000;
                b.setMessage(Component.literal(label + ": " + value.get()));
            }).bounds(x, y, w, 20).build();
        }

        @Override
        protected void init() {
            int colW = 150, gap = 10;
            int left = this.width / 2 - colW - gap / 2;
            int rightX = this.width / 2 + gap / 2;
            int y = 44, row = 21;

            // Left column: general + list
            addRenderableWidget(toggle(left, y, colW, "Mod", () -> enabled,
                    () -> setEnabled(Minecraft.getInstance(), !enabled)));
            addRenderableWidget(toggle(left, y + row, colW, "Share my position", () -> sharing, () -> {
                if (!sharing && (crypto == null)) return;
                sharing = !sharing;
                if (sharing) lastSendMs = 0; else sendBye();
            }));
            addRenderableWidget(cycle(left, y + row * 2, colW, "Update speed",
                    () -> intervalMs + " ms", () -> intervalMs = next(SPEED, intervalMs)));
            addRenderableWidget(toggle(left, y + row * 3, colW, "Team list", () -> showList,
                    () -> showList = !showList));
            addRenderableWidget(cycle(left, y + row * 4, colW, "List corner",
                    () -> hudCorner, () -> {
                        int i = java.util.Arrays.asList(CORNERS).indexOf(hudCorner);
                        hudCorner = CORNERS[(i + 1) % CORNERS.length];
                        hudOffsetX = 4;
                        hudOffsetY = 4;
                    }));
            addRenderableWidget(cycle(left, y + row * 5, colW, "List size",
                    () -> Math.round(hudScale * 100) + "%",
                    () -> hudScale = next(SCALE, Math.round(hudScale * 100)) / 100f));

            // Right column: head markers
            addRenderableWidget(toggle(rightX, y, colW, "Head markers", () -> showHeads,
                    () -> showHeads = !showHeads));
            addRenderableWidget(toggle(rightX, y + row, colW, "Show distance", () -> showHeadDistance,
                    () -> showHeadDistance = !showHeadDistance));
            addRenderableWidget(toggle(rightX, y + row * 2, colW, "Show name", () -> showHeadName,
                    () -> showHeadName = !showHeadName));
            addRenderableWidget(toggle(rightX, y + row * 3, colW, "Off-screen at edge", () -> edgeMarkers,
                    () -> edgeMarkers = !edgeMarkers));
            addRenderableWidget(cycle(rightX, y + row * 4, colW, "Head size",
                    () -> headSize + " px", () -> headSize = next(SIZES, headSize)));
            addRenderableWidget(cycle(rightX, y + row * 5, colW, "Hide closer than",
                    () -> hideCloser == 0 ? "never" : hideCloser + " blocks",
                    () -> hideCloser = next(HIDE, hideCloser)));
            addRenderableWidget(cycle(rightX, y + row * 6, colW, "Max distance",
                    () -> maxHeadDistance == 0 ? "unlimited" : maxHeadDistance + " blocks",
                    () -> maxHeadDistance = next(MAXD, maxHeadDistance)));
            addRenderableWidget(toggle(rightX, y + row * 7, colW, "Hide when visible", () -> hideWhenVisible,
                    () -> hideWhenVisible = !hideWhenVisible));

            addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                    .bounds(this.width / 2 - 100, this.height - 26, 200, 20).build());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(g, mouseX, mouseY, partialTick);
            g.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
            String pw = crypto != null ? "§aPassword set" : "§cNo password - use /tl team <password>";
            g.centeredText(this.font, Component.literal(pw), this.width / 2, 20, 0xFFFFFFFF);
            g.centeredText(this.font, Component.literal("§7General & list"), this.width / 2 - 80, 33, 0xFFFFFFFF);
            g.centeredText(this.font, Component.literal("§7Head markers"), this.width / 2 + 80, 33, 0xFFFFFFFF);
        }

        @Override
        public void onClose() {
            saveConfig();
            if (this.minecraft != null) this.minecraft.gui.setScreen(parent);
        }
    }

    // ======================================================================
    // Encryption: AES-256-GCM, key + topic derived from the password (PBKDF2)
    // ======================================================================
    static final class TeamCrypto {
        private static final SecureRandom RANDOM = new SecureRandom();
        final SecretKeySpec key;
        final String topic;

        private TeamCrypto(SecretKeySpec key, String topic) {
            this.key = key;
            this.topic = topic;
        }

        static TeamCrypto create(String password) {
            try {
                SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] k = f.generateSecret(new PBEKeySpec(password.toCharArray(),
                        "team-locator-v3".getBytes(StandardCharsets.UTF_8), 150_000, 384)).getEncoded();
                SecretKeySpec aes = new SecretKeySpec(Arrays.copyOfRange(k, 0, 32), "AES");
                String topic = "teamlocator/" + HexFormat.of().formatHex(Arrays.copyOfRange(k, 32, 48));
                return new TeamCrypto(aes, topic);
            } catch (Exception e) {
                System.err.println("[Team Locator] Key error: " + e);
                return null;
            }
        }

        byte[] encrypt(String plain) {
            try {
                byte[] iv = new byte[12];
                RANDOM.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
                byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
                byte[] out = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, out, 0, iv.length);
                System.arraycopy(ct, 0, out, iv.length, ct.length);
                return out;
            } catch (Exception e) {
                return null;
            }
        }

        String decrypt(byte[] data) {
            if (data == null || data.length < 12 + 16) return null;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
                return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null; // wrong key or tampered
            }
        }
    }

    // ======================================================================
    // Minimal MQTT 3.1.1 client over TLS (QoS 0 only, no extra libraries)
    // ======================================================================
    static final class MqttLink implements Runnable {
        private final String host;
        private final int port;
        private final String topic;
        private final Consumer<byte[]> onMessage;
        private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>(64);
        private volatile boolean running = true;
        private volatile boolean connected = false;
        private volatile SSLSocket socket;
        private Thread thread;

        MqttLink(String host, int port, String topic, Consumer<byte[]> onMessage) {
            this.host = host;
            this.port = port;
            this.topic = topic;
            this.onMessage = onMessage;
        }

        void start() {
            thread = new Thread(this, "TeamLocator-Link");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            connected = false;
            closeSocket();
            if (thread != null) thread.interrupt();
        }

        boolean isConnected() {
            return connected;
        }

        void publish(byte[] payload) {
            if (connected) outbox.offer(payload);
        }

        @Override
        public void run() {
            while (running) {
                try {
                    session();
                } catch (Exception e) {
                    if (running) System.err.println("[Team Locator] Connection: " + e.getMessage());
                }
                connected = false;
                closeSocket();
                if (!running) break;
                reconnects++;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        private void session() throws IOException {
            SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            SSLParameters params = s.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // verify certificate + hostname
            params.setServerNames(List.of(new SNIHostName(host)));
            s.setSSLParameters(params);
            socket = s;
            s.connect(new InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(15_000);
            s.startHandshake();

            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = new BufferedOutputStream(s.getOutputStream());

            // CONNECT
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeString(body, "MQTT");
            body.write(4);                 // protocol level 3.1.1
            body.write(0x02);              // clean session
            body.write(0); body.write(60); // keep-alive 60 s
            byte[] id = new byte[6];
            new SecureRandom().nextBytes(id);
            writeString(body, "tl-" + HexFormat.of().formatHex(id));
            writePacket(out, 0x10, body.toByteArray());

            int type = in.readUnsignedByte();
            int len = readLength(in);
            byte[] ack = new byte[len];
            in.readFully(ack);
            if ((type >> 4) != 2 || len < 2 || ack[1] != 0) throw new IOException("CONNACK refused");

            // SUBSCRIBE
            body.reset();
            body.write(0); body.write(1);  // packet id 1
            writeString(body, topic);
            body.write(0);                 // QoS 0
            writePacket(out, 0x82, body.toByteArray());

            s.setSoTimeout(0);
            connected = true;
            outbox.clear();

            Thread writer = new Thread(() -> writerLoop(out), "TeamLocator-Writer");
            writer.setDaemon(true);
            writer.start();
            try {
                while (running) {
                    int h = in.readUnsignedByte();
                    int l = readLength(in);
                    byte[] data = new byte[l];
                    in.readFully(data);
                    if ((h >> 4) == 3) { // PUBLISH
                        int qos = (h >> 1) & 3;
                        int tl = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                        int off = 2 + tl + (qos > 0 ? 2 : 0);
                        if (off <= data.length) {
                            try {
                                onMessage.accept(Arrays.copyOfRange(data, off, data.length));
                            } catch (Exception ignored) { }
                        }
                    }
                }
            } finally {
                connected = false;
                writer.interrupt();
            }
        }

        private void writerLoop(OutputStream out) {
            try {
                while (running && connected) {
                    byte[] payload = outbox.poll(25, TimeUnit.SECONDS);
                    if (payload == null) {
                        writePacket(out, 0xC0, new byte[0]); // PINGREQ
                    } else {
                        ByteArrayOutputStream b = new ByteArrayOutputStream();
                        writeString(b, topic);
                        b.write(payload);
                        writePacket(out, 0x30, b.toByteArray()); // PUBLISH QoS 0
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (IOException e) {
                closeSocket();
            }
        }

        static void writeString(ByteArrayOutputStream b, String s) {
            byte[] d = s.getBytes(StandardCharsets.UTF_8);
            b.write(d.length >> 8);
            b.write(d.length & 0xFF);
            b.write(d, 0, d.length);
        }

        static void writePacket(OutputStream out, int header, byte[] body) throws IOException {
            synchronized (out) {
                out.write(header);
                int len = body.length;
                do {
                    int digit = len % 128;
                    len /= 128;
                    if (len > 0) digit |= 0x80;
                    out.write(digit);
                } while (len > 0);
                out.write(body);
                out.flush();
            }
        }

        static int readLength(DataInputStream in) throws IOException {
            int mult = 1, value = 0, digit, count = 0;
            do {
                digit = in.readUnsignedByte();
                value += (digit & 127) * mult;
                mult *= 128;
                if (++count > 4) throw new IOException("Invalid length");
            } while ((digit & 128) != 0);
            return value;
        }

        private void closeSocket() {
            SSLSocket s = socket;
            if (s != null) {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }
}
