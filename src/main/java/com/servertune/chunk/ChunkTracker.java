package com.servertune.chunk;

import com.servertune.ServerTunePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Event-driven chunk tracking system.
 * Tracks loaded chunks, activity timestamps, player presence without expensive scans.
 */
public class ChunkTracker implements Listener {

    private final ServerTunePlugin plugin;

    // Chunk activity tracking
    private final Map<String, Map<Long, ChunkActivity>> chunksByWorld;

    // Load/unload rate tracking
    private final AtomicInteger loadsSinceLastCheck;
    private final AtomicInteger unloadsSinceLastCheck;

    // Load and unload rates use independent windows so reading one does not zero the other
    private volatile long lastLoadRateCheck;
    private volatile long lastUnloadRateCheck;
    private volatile int cachedLoadRate;
    private volatile int cachedUnloadRate;

    /**
     * Reused by {@link #countPlayersInChunk(World, int, int)}. Only ever touched on the main
     * thread, from the chunk-load handler and startup.
     */
    private final Location playerScratch = new Location(null, 0, 0, 0);

    public ChunkTracker(ServerTunePlugin plugin) {
        this.plugin = plugin;
        this.chunksByWorld = new ConcurrentHashMap<>();
        this.loadsSinceLastCheck = new AtomicInteger(0);
        this.unloadsSinceLastCheck = new AtomicInteger(0);

        long now = System.currentTimeMillis();
        this.lastLoadRateCheck = now;
        this.lastUnloadRateCheck = now;

        // Register listener
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Initialize with currently loaded chunks
        initializeLoadedChunks();
    }

    private void initializeLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            String worldName = world.getName();
            Map<Long, ChunkActivity> worldChunks = chunksByWorld.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());

            // One pass over the world's players, not one pass per loaded chunk. On a server
            // with thousands of loaded chunks the old per-chunk scan was chunks x players.
            Map<Long, Integer> playersByChunk = countPlayersByChunk(world);

            for (Chunk chunk : world.getLoadedChunks()) {
                long key = getChunkKey(chunk.getX(), chunk.getZ());
                ChunkActivity activity = new ChunkActivity(chunk);
                activity.setPlayerCount(playersByChunk.getOrDefault(key, 0));
                worldChunks.put(key, activity);
            }
        }

        plugin.getLogger().info(String.format("[ChunkTracker] Initialized with %d loaded chunks", getTotalLoadedChunks()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        long key = getChunkKey(chunk.getX(), chunk.getZ());

        Map<Long, ChunkActivity> worldChunks = chunksByWorld.computeIfAbsent(worldName, k -> new ConcurrentHashMap<>());

        ChunkActivity activity = new ChunkActivity(chunk);

        // A chunk usually loads because a player approached it, so this is normally 0 or 1
        // and the world's player list is short. Still bounded by online players, not chunks.
        activity.setPlayerCount(countPlayersInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ()));

        worldChunks.put(key, activity);
        loadsSinceLastCheck.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        long key = getChunkKey(chunk.getX(), chunk.getZ());

        Map<Long, ChunkActivity> worldChunks = chunksByWorld.get(worldName);
        if (worldChunks != null) {
            worldChunks.remove(key);
        }

        unloadsSinceLastCheck.incrementAndGet();
    }

    /**
     * Player chunk transitions.
     *
     * <p>This is the hottest handler in the plugin - {@code PlayerMoveEvent} fires for every
     * movement packet, several times per second per player. The early-out therefore compares
     * chunk coordinates derived from the raw block coordinates. Calling
     * {@code Location#getChunk()} first, as this used to, resolved two chunk objects on every
     * packet just to discover that the player had not left their chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        int fromChunkX = from.getBlockX() >> 4;
        int fromChunkZ = from.getBlockZ() >> 4;
        int toChunkX = to.getBlockX() >> 4;
        int toChunkZ = to.getBlockZ() >> 4;

        if (fromChunkX == toChunkX && fromChunkZ == toChunkZ) {
            return;
        }

        String worldName = to.getWorld().getName();

        ChunkActivity newActivity = getChunkActivity(worldName, toChunkX, toChunkZ);
        if (newActivity != null) {
            newActivity.updatePlayerActivity();
            newActivity.incrementPlayerCount();
        }

        // A world change makes "from" a chunk in the other world, so resolve it there.
        ChunkActivity oldActivity =
                getChunkActivity(from.getWorld().getName(), fromChunkX, fromChunkZ);
        if (oldActivity != null) {
            oldActivity.decrementPlayerCount();
        }
    }

    /**
     * Player count for one chunk, by coordinate comparison.
     *
     * <p>{@code player.getLocation().getChunk()} per player allocated a Location and resolved
     * a Chunk for every online player, and this runs once per {@code ChunkLoadEvent}.
     */
    private int countPlayersInChunk(World world, int chunkX, int chunkZ) {
        int count = 0;
        for (Player player : world.getPlayers()) {
            player.getLocation(playerScratch);
            if ((playerScratch.getBlockX() >> 4) == chunkX
                    && (playerScratch.getBlockZ() >> 4) == chunkZ) {
                count++;
            }
        }
        return count;
    }

    /** Chunk key -> player count for one world, computed in a single pass over its players. */
    private Map<Long, Integer> countPlayersByChunk(World world) {
        Map<Long, Integer> counts = new java.util.HashMap<>();
        for (Player player : world.getPlayers()) {
            player.getLocation(playerScratch);
            long key = getChunkKey(playerScratch.getBlockX() >> 4, playerScratch.getBlockZ() >> 4);
            counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    public ChunkActivity getChunkActivity(Chunk chunk) {
        Map<Long, ChunkActivity> worldChunks = chunksByWorld.get(chunk.getWorld().getName());
        if (worldChunks == null) {
            return null;
        }
        return worldChunks.get(getChunkKey(chunk.getX(), chunk.getZ()));
    }

    public ChunkActivity getChunkActivity(String worldName, int chunkX, int chunkZ) {
        Map<Long, ChunkActivity> worldChunks = chunksByWorld.get(worldName);
        if (worldChunks == null) {
            return null;
        }
        return worldChunks.get(getChunkKey(chunkX, chunkZ));
    }

    public Map<Long, ChunkActivity> getWorldChunks(String worldName) {
        return chunksByWorld.get(worldName);
    }

    /**
     * Tracked loaded-chunk count for one world.
     *
     * <p>Exists so callers do not reach for {@code World#getLoadedChunks().length}, which
     * allocates a {@code Chunk[]} holding every loaded chunk in the world - thousands of
     * entries on a busy server - just to read its length.
     */
    public int getLoadedChunkCount(String worldName) {
        Map<Long, ChunkActivity> worldChunks = chunksByWorld.get(worldName);
        return worldChunks == null ? 0 : worldChunks.size();
    }

    public int getTotalLoadedChunks() {
        return chunksByWorld.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    public int getChunkLoadRate() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastLoadRateCheck;

        // Window too short to measure; report the last known rate
        if (elapsed < 1000) {
            return cachedLoadRate;
        }

        int loads = loadsSinceLastCheck.getAndSet(0);
        lastLoadRateCheck = now;
        cachedLoadRate = (int) (loads * 1000L / elapsed);

        return cachedLoadRate;
    }

    public int getChunkUnloadRate() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastUnloadRateCheck;

        if (elapsed < 1000) {
            return cachedUnloadRate;
        }

        int unloads = unloadsSinceLastCheck.getAndSet(0);
        lastUnloadRateCheck = now;
        cachedUnloadRate = (int) (unloads * 1000L / elapsed);

        return cachedUnloadRate;
    }

    private long getChunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Releases the three event handlers and drops the per-chunk activity records. The unregister
     * matters most for {@code onPlayerMove}, which is one of the highest-frequency events on the
     * server: left registered after a disable it kept running against a cleared map.
     */
    public void shutdown() {
        HandlerList.unregisterAll(this);
        chunksByWorld.clear();
    }
}
