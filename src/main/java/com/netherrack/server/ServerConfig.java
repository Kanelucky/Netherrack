package com.netherrack.server;

import com.netherrack.server.setup.Lang;
import com.netherrack.server.util.Logger;
import com.netherrack.server.world.FlatPreset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

public class ServerConfig {

    /**
     * Every setting a new server.properties gets, in order, with its default value and the
     * comment written under it - laid out the way Bedrock Dedicated Server lays out its own.
     * The language is the one picked in the setup wizard.
     */
    private static List<Setting> defaults(String language) {
        String languages = Lang.AVAILABLE.keySet().stream()
                .map(code -> "\"" + code + "\"")
                .collect(Collectors.joining(" or "));
        List<String> names = Arrays.stream(FlatPreset.values())
                .map(preset -> "\"" + preset.getDisplayName() + "\"")
                .toList();
        String presets = String.join(", ", names.subList(0, names.size() - 1)) + ", or " + names.get(names.size() - 1);
        return List.of(
                new Setting("server-name", "Netherrack Server",
                        "Used as the server name",
                        "Allowed values: Any string without semicolon symbol"),
                new Setting("gamemode", "survival",
                        "Sets the game mode for new players",
                        "Allowed values: \"survival\", \"creative\", or \"adventure\""),
                new Setting("difficulty", "normal",
                        "Sets the difficulty of the world",
                        "Allowed values: \"peaceful\", \"easy\", \"normal\", or \"hard\""),
                new Setting("max-players", "20",
                        "The maximum number of players that can play on the server",
                        "Allowed values: Any positive integer"),
                new Setting("online-mode", "true",
                        "If true then all connected players must be authenticated to Xbox Live.",
                        "Clients connecting to remote (non-LAN) servers will always require Xbox Live authentication"
                                + " regardless of this setting.",
                        "If the server accepts connections from the Internet, then it's highly recommended to enable"
                                + " online-mode.",
                        "Allowed values: \"true\" or \"false\""),
                new Setting("server-port", "19132",
                        "Which IPv4 port the server should listen to.",
                        "Allowed values: Integers in the range [1, 65535]"),
                new Setting("server-ip", "0.0.0.0",
                        "Which IP address the server should listen on. 0.0.0.0 listens on every address this"
                                + " machine has.",
                        "Allowed values: An IPv4 or IPv6 literal, or empty."),
                new Setting("view-distance", "32",
                        "The maximum allowed view distance in number of chunks.",
                        "Allowed values: Positive integer equal to 5 or greater."),
                new Setting("level-name", "Bedrock level",
                        "The name of the level to play on, which is also the name of its folder in worlds."
                                + " If there's no such level, a new one is created.",
                        "Allowed values: Any string without semicolon symbol or symbols illegal for file name:"
                                + " /\\n\\r\\t\\f`?*\\\\<>|\\\":"),
                new Setting("level-type", "flat",
                        "The type of world to create when the level doesn't exist yet. A level that already exists"
                                + " keeps the type it was created with.",
                        "Allowed values: \"flat\" (its layers are set by flat-world-preset), or \"default\" for"
                                + " generated terrain (not available yet: until it is, the world stays empty)",
                        "Commenting this out is the same as \"default\"."),
                new Setting("flat-world-preset", FlatPreset.CLASSIC_FLAT.getDisplayName(),
                        "Which of the game's flat world presets a new flat level is made from, which sets the"
                                + " blocks of each layer from the bottom of the world up. Only used when level-type is"
                                + " \"flat\".",
                        "Allowed values: " + presets),
                new Setting("default-player-permission-level", "member",
                        "Permission level for new players joining for the first time.",
                        "Allowed values: \"visitor\", \"member\", \"operator\""),
                new Setting("op-permission-level", "2",
                        "Which commands operators can use: 0 only the ones every player can, 2 adds commands"
                                + " like gamemode, 3 adds op and deop, and 4 adds stop.",
                        "Who is an operator is set in permissions.json, or with the op and deop commands.",
                        "Allowed values: Integers in the range [0, 4]"),
                new Setting("language", language,
                        "The language of the server's own messages in the console. Players see the game in the"
                                + " language they've chosen themselves.",
                        "Allowed values: " + languages),
                new Setting("debug-logs", "false",
                        "If true, every network packet and other debugging detail is written to logs/latest.log as"
                                + " well, without showing in the console. Useful when something goes wrong, but the"
                                + " log grows quickly.",
                        "Allowed values: \"true\" or \"false\""));
    }

    private record Setting(String key, String value, String... comment) {
    }

    private final Properties properties = new Properties();
    private final Path path;

    public ServerConfig(Path path) {
        this.path = path;
    }

    /**
     * @param language language code to save into a brand-new server.properties (e.g. the one
     *                 just picked in the setup wizard). Ignored if the file already exists.
     */
    public void load(String language) {
        if (!Files.exists(path)) {
            Logger.warn(Lang.current.get("server.config_missing"));
            writeDefaults(language);
        }
        try (InputStream in = Files.newInputStream(path)) {
            properties.load(in);
        } catch (IOException e) {
            Logger.error("Failed to load server.properties: " + e.getMessage());
        }
    }

    /** Writes a new server.properties: each setting, then what it does, then a blank line. */
    private void writeDefaults(String language) {
        String newline = System.lineSeparator();
        StringBuilder text = new StringBuilder();
        for (Setting setting : defaults(language)) {
            text.append(setting.key()).append('=').append(setting.value()).append(newline);
            for (String line : setting.comment()) {
                text.append("# ").append(line).append(newline);
            }
            text.append(newline);
        }
        try {
            // Properties files are read as ISO 8859-1; everything written here is plain ASCII.
            Files.writeString(path, text, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            Logger.error("Failed to write default server.properties: " + e.getMessage());
        }
    }

    public String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(defaultValue)));
    }
}
