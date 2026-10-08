package com.example.commitgap.runtime;

import com.example.commitgap.core.CommitGapVersion;
import com.example.commitgap.core.result.EnvironmentInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.DockerClientFactory;

/** Collects the versions and identities that a run's result depends on. */
public final class EnvironmentProbe {

    private EnvironmentProbe() {
    }

    public static EnvironmentInfo describe(String namespace, Path demoJar, List<String> resources) {
        Map<String, String> imageIds = new LinkedHashMap<>();
        String dockerVersion = "unavailable";
        try {
            dockerVersion = DockerClientFactory.instance().getInfo().getServerVersion();
            for (Map.Entry<String, String> image : LabImages.all().entrySet()) {
                try {
                    imageIds.put(image.getKey(),
                            DockerClientFactory.instance().client().inspectImageCmd(image.getValue()).exec().getId());
                } catch (RuntimeException e) {
                    imageIds.put(image.getKey(), "not present locally");
                }
            }
        } catch (RuntimeException e) {
            // Docker unavailable: recorded as such
        }
        Map<String, String> components = new LinkedHashMap<>();
        components.put("spring-boot", CommitGapVersion.component("spring-boot"));
        components.put("testcontainers", CommitGapVersion.component("testcontainers"));
        components.put("picocli", CommitGapVersion.component("picocli"));
        return new EnvironmentInfo(
                CommitGapVersion.version(),
                System.getProperty("java.vendor") + " " + System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")",
                dockerVersion,
                namespace,
                LabImages.all(),
                imageIds,
                sha256(demoJar),
                components,
                resources);
    }

    static String sha256(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "missing";
        }
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unreadable: " + e.getMessage();
        }
    }
}
