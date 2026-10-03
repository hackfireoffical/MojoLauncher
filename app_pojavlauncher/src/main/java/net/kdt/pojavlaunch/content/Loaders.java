package net.kdt.pojavlaunch.content;

import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;

import java.util.List;
import java.util.Locale;

/** Loader and Minecraft-version helpers shared by the whole content system. */
public final class Loaders {
    private Loaders() {}

    public static String normalize(String loader) {
        if (loader == null) return null;
        String normalized = loader.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    /** Shaders and resource packs have no mod loader, so they are always compatible. */
    public static boolean isCompatible(String contentType, String wanted, List<String> supported) {
        if (!Constants.CONTENT_MOD.equals(contentType)) return true;
        String want = normalize(wanted);
        if (want == null || want.equals("unknown") || want.equals("vanilla")) return true;
        if (supported == null || supported.isEmpty()) return true;
        for (String loader : supported) {
            String have = normalize(loader);
            if (have == null) continue;
            if (have.equals(want)) return true;
            // Quilt runs Fabric mods
            if (want.equals("quilt") && have.equals("fabric")) return true;
        }
        return false;
    }

    public static String display(String loader) {
        String n = normalize(loader);
        if (n == null) return "Unknown";
        switch (n) {
            case "neoforge": return "NeoForge";
            case "fabric": return "Fabric";
            case "forge": return "Forge";
            case "quilt": return "Quilt";
            default: return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
    }

    /** Detects the mod loader from an instance's version id, or null for vanilla/unknown. */
    public static String fromVersionId(String versionId) {
        if (versionId == null) return null;
        String lower = versionId.toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge")) return "neoforge";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("fabric")) return "fabric";
        if (lower.contains("forge")) return "forge";
        return null;
    }

    /**
     * Extracts the Minecraft version from an instance's version id, e.g.
     * fabric-loader-0.16.9-1.21.1 -> 1.21.1, 1.20.1-forge-47.2.0 -> 1.20.1, neoforge-21.1.50 -> 1.21.1.
     * Returns null when it cannot be determined.
     */
    public static String minecraftVersion(String versionId) {
        if (versionId == null) return null;
        String found = null;
        String snapshot = null;
        for (String token : versionId.split("-")) {
            if (isMinecraftRelease(token)) found = token;
            else if (isSnapshot(token)) snapshot = token;
        }
        if (found != null) return found;
        String neoForge = neoForgeMinecraft(versionId);
        if (neoForge != null) return neoForge;
        return snapshot;
    }

    /**
     * NeoForge ids do not contain the Minecraft version but encode it: 21.1.x is 1.21.1,
     * 21.0.x is 1.21, 20.4.x is 1.20.4. From 26.x on the numbers match Minecraft directly (best effort).
     */
    private static String neoForgeMinecraft(String versionId) {
        String lower = versionId.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("neoforge");
        if (at < 0) return null;
        int i = at + 8;
        int length = lower.length();
        if (i < length && (lower.charAt(i) == '-' || lower.charAt(i) == '_')) i++;
        int majorStart = i;
        while (i < length && Character.isDigit(lower.charAt(i))) i++;
        if (i == majorStart || i >= length || lower.charAt(i) != '.') return null;
        long major = parseLongSafe(lower.substring(majorStart, i));
        i++;
        int minorStart = i;
        while (i < length && Character.isDigit(lower.charAt(i))) i++;
        if (i == minorStart) return null;
        long minor = parseLongSafe(lower.substring(minorStart, i));
        if (major < 20 || minor < 0) return null;
        if (major >= 26) return major + "." + minor;
        return minor == 0 ? "1." + major : "1." + major + "." + minor;
    }

    /** True for release versions like 1.21.1 or 26.2 (not snapshots, pre-releases or loader versions). */
    public static boolean isMinecraftRelease(String version) {
        if (version == null) return false;
        String[] parts = version.split("[.]");
        if (parts.length < 2 || parts.length > 3) return false;
        long[] numbers = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            numbers[i] = parseLongSafe(parts[i]);
            if (numbers[i] < 0 || parts[i].length() > 3) return false;
        }
        if (numbers[0] == 1) return true;
        return numbers[0] >= 26 && numbers[0] <= 29;
    }

    private static boolean isSnapshot(String token) {
        if (token == null || token.length() != 6) return false;
        return Character.isDigit(token.charAt(0)) && Character.isDigit(token.charAt(1))
                && token.charAt(2) == 'w'
                && Character.isDigit(token.charAt(3)) && Character.isDigit(token.charAt(4))
                && Character.isLetter(token.charAt(5));
    }

    public static boolean isMinecraftVersion(String version) {
        return isMinecraftRelease(version) || isSnapshot(version);
    }

    /** Orders Minecraft versions newest first. Snapshots and unknown formats sink to the bottom. */
    public static int compareMinecraftDesc(String a, String b) {
        String[] pa = a.split("[.]");
        String[] pb = b.split("[.]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long x = i < pa.length ? parseLongSafe(pa[i]) : 0;
            long y = i < pb.length ? parseLongSafe(pb[i]) : 0;
            if (x != y) return Long.compare(y, x);
        }
        return a.compareTo(b);
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
