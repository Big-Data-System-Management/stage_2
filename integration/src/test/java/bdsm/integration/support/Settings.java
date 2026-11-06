package bdsm.integration.support;

import bdsm.common.config.Config;

import java.nio.file.Path;

public final class Settings {

    public static final Path ROOT = Config.getPath("STACK_ROOT", "..").toAbsolutePath().normalize();
    public static final Path DATALAKE = ROOT.resolve(Config.get("DATALAKE_PATH", "datalake"));
    public static final Path SAMPLE_DATASET = ROOT.resolve("sample_dataset");
    public static final Path FEEDER_JAR = ROOT.resolve("feeder/target/feeder-1.0.0.jar");
    public static final Path LOG_DIR = ROOT.resolve(Config.get("STACK_LOG_DIR", "benchmark/logs/stack"));
    public static final String STACK_BASH = Config.get("STACK_BASH", "bash");
    public static final String BROKER_URL = Config.get("BROKER_URL", "tcp://localhost:61616");
    public static final String MONGO_URI = Config.get("MONGO_URI", "mongodb://localhost:27017");
    public static final String MONGO_DATABASE = Config.get("MONGO_DATABASE", "search_engine");
    public static final String SEARCH_URL = url("SEARCH_PORT", 7003);
    public static final String INDEXING_URL = url("INDEXING_PORT", 7002);
    public static final String INGESTION_URL = url("INGESTION_PORT", 7001);

    private Settings() {
    }

    private static String url(String setting, int defaultPort) {
        return "http://localhost:" + Config.getInt(setting, defaultPort);
    }
}
