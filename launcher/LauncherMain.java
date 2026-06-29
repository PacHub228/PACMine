import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PACMine launcher: downloads the latest game from GitHub, fetches LWJGL,
 * compiles it and runs it. Single-file Swing app.
 */
public class LauncherMain {
    static final String REPO_ZIP = "https://github.com/PacHub228/PACMine/archive/refs/heads/main.zip";
    static final String MESA_ZIP = "https://raw.githubusercontent.com/PacHub228/PACMine/mesa/mesa-win.zip";
    static final Path HOME    = Paths.get(System.getProperty("user.home"), ".pacmine");
    static final Path GAMEDIR = HOME.resolve("PACMine");
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    static JCheckBox swGl;

    /** Command to invoke a project script (get-deps / build / run) on this OS. */
    static String[] script(String name) {
        return WINDOWS ? new String[]{"cmd", "/c", name + ".bat"}
                       : new String[]{"bash", name + ".sh"};
    }

    static JLabel status;
    static JProgressBar bar;
    static JButton play, update;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(LauncherMain::buildUi);
    }

    static void buildUi() {
        JFrame f = new JFrame("PACMine Launcher");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(460, 240);
        f.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.setBackground(new Color(0x20242E));

        JLabel title = new JLabel("PACMINE", SwingConstants.CENTER);
        title.setFont(new Font("Monospaced", Font.BOLD, 36));
        title.setForeground(new Color(0xD9F299));
        root.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel(new GridLayout(3, 1, 6, 6));
        center.setOpaque(false);
        status = new JLabel("Ready", SwingConstants.CENTER);
        status.setForeground(Color.WHITE);
        bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        swGl = new JCheckBox("Software OpenGL (for virtual machines)");
        swGl.setOpaque(false);
        swGl.setForeground(Color.WHITE);
        swGl.setHorizontalAlignment(SwingConstants.CENTER);
        swGl.setVisible(WINDOWS);
        center.add(status);
        center.add(bar);
        center.add(swGl);
        root.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(1, 2, 10, 0));
        buttons.setOpaque(false);
        update = new JButton("UPDATE");
        play = new JButton("PLAY");
        play.setFont(new Font("SansSerif", Font.BOLD, 16));
        update.addActionListener(e -> run(true));
        play.addActionListener(e -> run(false));
        buttons.add(update);
        buttons.add(play);
        root.add(buttons, BorderLayout.SOUTH);

        f.setContentPane(root);
        f.setVisible(true);
    }

    /** updateOnly=true: just fetch/build. false: also launch the game. */
    static void run(boolean updateOnly) {
        play.setEnabled(false); update.setEnabled(false);
        new Thread(() -> {
            try {
                boolean installed = Files.exists(GAMEDIR.resolve("src/com/voxel/Main.java"));
                if (updateOnly || !installed) {
                    setStatus("Downloading game...", 5);
                    Path zip = HOME.resolve("game.zip");
                    Files.createDirectories(HOME);
                    download(REPO_ZIP, zip);
                    setStatus("Extracting...", 45);
                    extract(zip, HOME);
                    // GitHub names the folder PACMine-main; move it into place
                    Path extracted = HOME.resolve("PACMine-main");
                    if (Files.exists(extracted)) {
                        deleteDir(GAMEDIR);
                        Files.move(extracted, GAMEDIR);
                    }
                    Files.deleteIfExists(zip);
                    setStatus("Fetching libraries...", 60);
                    exec(GAMEDIR, script("get-deps"));
                    setStatus("Compiling...", 80);
                    exec(GAMEDIR, script("build"));
                }
                if (updateOnly) {
                    setStatus("Up to date!", 100);
                } else {
                    if (WINDOWS && swGl.isSelected()) {
                        setStatus("Installing software OpenGL...", 90);
                        ensureMesa();
                    }
                    setStatus("Launching...", 100);
                    exec(GAMEDIR, script("run"));
                    setStatus("Ready", 0);
                }
            } catch (Exception ex) {
                setStatus("Error: " + ex.getMessage(), 0);
                ex.printStackTrace();
            } finally {
                SwingUtilities.invokeLater(() -> { play.setEnabled(true); update.setEnabled(true); });
            }
        }).start();
    }

    /**
     * Download Mesa's software OpenGL and place opengl32.dll + libgallium_wgl.dll
     * next to java.exe (the only spot Windows searches before System32).
     */
    static void ensureMesa() throws IOException {
        Path binDir = Paths.get(System.getProperty("java.home"), "bin");
        if (Files.exists(binDir.resolve("opengl32.dll")) && Files.exists(binDir.resolve("libgallium_wgl.dll")))
            return; // already installed
        Path zip = HOME.resolve("mesa-win.zip");
        download(MESA_ZIP, zip);
        try {
            extract(zip, binDir);              // preferred: beside java.exe
        } catch (IOException e) {
            // no permission to JDK bin: fall back to the game dir (works if cwd is searched)
            extract(zip, GAMEDIR);
            throw new IOException("Could not write to the JDK; placed DLLs in the game folder instead. "
                    + "If OpenGL still fails, run the launcher as administrator.");
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    static void setStatus(String s, int pct) {
        SwingUtilities.invokeLater(() -> { status.setText(s); bar.setValue(pct); });
    }

    static void download(String url, Path dest) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PACMine-Launcher");
        try (InputStream in = c.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void extract(Path zip, Path destDir) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                Path out = destDir.resolve(e.getName()).normalize();
                if (!out.startsWith(destDir)) continue; // zip-slip guard
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    static void exec(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).inheritIO();
        Process p = pb.start();
        int code = p.waitFor();
        if (code != 0) throw new IOException(cmd[cmd.length - 1] + " failed (exit " + code + ")");
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }
}
