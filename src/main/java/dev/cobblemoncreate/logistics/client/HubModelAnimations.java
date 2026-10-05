package dev.cobblemoncreate.logistics.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.cobblemoncreate.logistics.CobblemonCreateLogistics;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Samples the same numeric keyframes exported from the reviewed Blockbench model. */
final class HubModelAnimations {
    record Transform(Vec3 position, Vec3 scale) {
        static final Transform REST = new Transform(Vec3.ZERO, new Vec3(1, 1, 1));

        // Concurrent transactions share the mechanism; they do not form a replay queue.
        Transform merge(Transform other) {
            return new Transform(new Vec3(larger(position.x, other.position.x),
                    larger(position.y, other.position.y), larger(position.z, other.position.z)),
                    new Vec3(Math.min(scale.x, other.scale.x), Math.min(scale.y, other.scale.y),
                            Math.min(scale.z, other.scale.z)));
        }

        private static double larger(double a, double b) { return Math.abs(a) > Math.abs(b) ? a : b; }
    }

    private record Key(double time, Vec3 value, boolean step) {}
    private record Track(List<Key> position, List<Key> scale) {}
    private record Clip(double length, Map<String, Track> tracks) {}

    private final Map<String, Vec3> pivots = new HashMap<>();
    private final Map<String, Clip> clips = new HashMap<>();
    private Vec3 cargoAnchor = new Vec3(0.5, 13.1 / 16, 0.5);

    static HubModelAnimations load() {
        HubModelAnimations result = new HubModelAnimations();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                CobblemonCreateLogistics.MOD_ID, "animations/hub.json");
        try (Reader reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location).openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            root.getAsJsonObject("pivots").entrySet().forEach(e ->
                    result.pivots.put(e.getKey(), vector(e.getValue().getAsJsonArray())));
            result.cargoAnchor = vector(root.getAsJsonArray("cargoAnchor"));
            root.getAsJsonObject("clips").entrySet().forEach(e -> {
                JsonObject clip = e.getValue().getAsJsonObject();
                Map<String, Track> tracks = new HashMap<>();
                clip.getAsJsonObject("tracks").entrySet().forEach(t -> {
                    JsonObject channels = t.getValue().getAsJsonObject();
                    tracks.put(t.getKey(), new Track(keys(channels, "position"), keys(channels, "scale")));
                });
                result.clips.put(e.getKey(), new Clip(clip.get("length").getAsDouble(), Map.copyOf(tracks)));
            });
        } catch (Exception e) {
            CobblemonCreateLogistics.LOGGER.error("Could not load Hub animation keyframes; using the rest pose", e);
        }
        return result;
    }

    Vec3 pivot(String part) { return pivots.getOrDefault(part, Vec3.ZERO); }
    Vec3 cargoAnchor() { return cargoAnchor; }

    Transform sample(String clipName, String part, double seconds) {
        Clip clip = clips.get(clipName);
        if (clip == null || seconds < 0 || seconds > clip.length()) return Transform.REST;
        Track track = clip.tracks().get(part);
        if (track == null) return Transform.REST;
        return new Transform(sampleKeys(track.position(), seconds, Vec3.ZERO),
                sampleKeys(track.scale(), seconds, Transform.REST.scale()));
    }

    private static Vec3 sampleKeys(List<Key> keys, double time, Vec3 fallback) {
        if (keys.isEmpty()) return fallback;
        Key previous = keys.getFirst();
        if (time <= previous.time()) return previous.value();
        for (int i = 1; i < keys.size(); i++) {
            Key next = keys.get(i);
            if (time < next.time()) {
                if (previous.step()) return previous.value();
                double ratio = (time - previous.time()) / (next.time() - previous.time());
                return previous.value().lerp(next.value(), ratio);
            }
            previous = next;
        }
        return previous.value();
    }

    private static List<Key> keys(JsonObject channels, String channel) {
        List<Key> result = new ArrayList<>();
        if (channels.has(channel)) channels.getAsJsonArray(channel).forEach(e -> {
            JsonObject key = e.getAsJsonObject();
            result.add(new Key(key.get("time").getAsDouble(), vector(key.getAsJsonArray("value")),
                    key.get("step").getAsBoolean()));
        });
        return List.copyOf(result);
    }

    private static Vec3 vector(JsonArray value) {
        return new Vec3(value.get(0).getAsDouble(), value.get(1).getAsDouble(), value.get(2).getAsDouble());
    }
}
