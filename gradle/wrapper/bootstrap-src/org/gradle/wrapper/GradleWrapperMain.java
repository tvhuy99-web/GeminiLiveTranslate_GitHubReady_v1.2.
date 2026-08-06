package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * Minimal transparent Gradle bootstrap used because the project was generated
 * in an offline environment. It downloads the pinned Gradle distribution,
 * verifies SHA-256, prevents zip-slip, and delegates to Gradle's executable.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        File jar = new File(GradleWrapperMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        File appHome = jar.getParentFile().getParentFile().getParentFile();
        File propsFile = new File(appHome, "gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = new FileInputStream(propsFile)) { props.load(in); }

        String distributionUrl = props.getProperty("distributionUrl");
        String expectedChecksum = props.getProperty("distributionSha256Sum", "").trim().toLowerCase(Locale.ROOT);
        if (distributionUrl == null || distributionUrl.isBlank()) {
            throw new IllegalStateException("distributionUrl is missing");
        }
        distributionUrl = distributionUrl.replace("\\:", ":");

        String gradleUserHome = System.getenv("GRADLE_USER_HOME");
        if (gradleUserHome == null || gradleUserHome.isBlank()) {
            gradleUserHome = new File(System.getProperty("user.home"), ".gradle").getAbsolutePath();
        }
        String hash = sha256(distributionUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 16);
        String fileName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
        File base = new File(gradleUserHome, "wrapper/dists/verified-bootstrap/" + hash);
        File zip = new File(base, fileName);
        File marker = new File(base, ".installed");

        if (!marker.isFile()) {
            base.mkdirs();
            if (!zip.isFile() || !matchesChecksum(zip, expectedChecksum)) {
                Files.deleteIfExists(zip.toPath());
                download(distributionUrl, zip);
            }
            verifyChecksum(zip, expectedChecksum);
            unzip(zip, base);
            if (!marker.createNewFile() && !marker.isFile()) {
                throw new IOException("Cannot create installation marker: " + marker);
            }
        }

        File gradleHome = findGradleHome(base);
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        File executable = new File(gradleHome, windows ? "bin/gradle.bat" : "bin/gradle");
        if (!executable.isFile()) throw new FileNotFoundException("Gradle executable not found: " + executable);
        if (!windows && !executable.setExecutable(true) && !executable.canExecute()) {
            throw new IOException("Cannot make Gradle executable: " + executable);
        }

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
            .directory(new File(System.getProperty("user.dir")))
            .inheritIO()
            .start();
        System.exit(process.waitFor());
    }

    private static void download(String url, File target) throws IOException {
        System.out.println("Downloading " + url);
        File part = new File(target.getAbsolutePath() + ".part");
        Files.deleteIfExists(part.toPath());
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(new FileOutputStream(part))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        }
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void verifyChecksum(File file, String expected) throws Exception {
        if (expected.isBlank()) throw new SecurityException("distributionSha256Sum is required");
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new SecurityException("Gradle distribution checksum mismatch. Expected " + expected + ", got " + actual);
        }
    }

    private static boolean matchesChecksum(File file, String expected) {
        if (expected.isBlank() || !file.isFile()) return false;
        try { return sha256(file).equalsIgnoreCase(expected); }
        catch (Exception ignored) { return false; }
    }

    private static void unzip(File zip, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = in.getNextEntry()) != null) {
                File out = new File(destination, entry.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IOException("Cannot create directory: " + out);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Cannot create directory: " + parent);
                }
                try (OutputStream stream = new BufferedOutputStream(new FileOutputStream(out))) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) stream.write(buffer, 0, read);
                }
            }
        }
    }

    private static File findGradleHome(File base) throws IOException {
        File[] children = base.listFiles(file -> file.isDirectory() && file.getName().startsWith("gradle-"));
        if (children == null || children.length == 0) {
            throw new FileNotFoundException("Extracted Gradle directory not found in " + base);
        }
        Arrays.sort(children, Comparator.comparing(File::getName));
        return children[children.length - 1];
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
    }
}
