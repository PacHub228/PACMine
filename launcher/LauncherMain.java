import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PACMine launcher: downloads, builds and runs the game from GitHub.
 * Supports version selection, update checking, a build log and saved settings.
 */
public class LauncherMain {
    static final String REPO = "PacHub228/PACMine";
    static final String MESA_ZIP = "https://raw.githubusercontent.com/PacHub228/PACMine/mesa/mesa-win.zip";
    static final String BACKEND = "http://185.218.137.116";
    static final Path HOME    = Paths.get(System.getProperty("user.home"), ".pacmine");
    static final Path GAMEDIR = HOME.resolve("PACMine");
    static final Path CFG     = HOME.resolve("launcher.properties");
    static final Path PROFILE = HOME.resolve("profile.properties");
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    static final Color BG = new Color(0x20242E), PANEL = new Color(0x2A2F3B),
                       ACCENT = new Color(0xD9F299), TEXT = new Color(0xE6E8EC), SUB = new Color(0x9AA0AC);

    static JLabel status, versionInfo, account;
    static JProgressBar bar;
    static JButton play, update, openFolder, loginBtn, guestBtn;
    static JComboBox<String> versionBox;
    static JCheckBox swGl;
    static JTextArea log;
    static final Properties cfg = new Properties();

    public static void main(String[] args) {
        loadCfg();
        SwingUtilities.invokeLater(LauncherMain::buildUi);
    }

    // ---------------- UI ----------------
    static void buildUi() {
        JFrame f = new JFrame("PACMine Launcher");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setSize(620, 460);
        f.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        root.setBackground(BG);

        // header
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("PACMINE");
        title.setFont(new Font("Monospaced", Font.BOLD, 40));
        title.setForeground(ACCENT);
        versionInfo = new JLabel("checking...");
        versionInfo.setForeground(SUB);
        versionInfo.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(title, BorderLayout.WEST);
        header.add(versionInfo, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        // center: controls + log
        JPanel center = new JPanel(new BorderLayout(8, 8));
        center.setOpaque(false);

        JPanel controls = new JPanel(new GridBagLayout());
        controls.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 4, 4, 4); g.fill = GridBagConstraints.HORIZONTAL; g.gridx = 0; g.gridy = 0;

        JLabel vlab = new JLabel("Version:"); vlab.setForeground(TEXT);
        controls.add(vlab, g);
        versionBox = new JComboBox<>(new String[]{MAIN_LABEL});
        versionBox.setSelectedItem(MAIN_LABEL);
        versionBox.addActionListener(e -> { saveCfg(); checkUpdateAsync(); });
        g.gridx = 1; g.weightx = 1; controls.add(versionBox, g);

        swGl = new JCheckBox("Software OpenGL (for virtual machines)");
        swGl.setOpaque(false); swGl.setForeground(TEXT);
        swGl.setSelected(Boolean.parseBoolean(cfg.getProperty("vm", "false")));
        swGl.setVisible(WINDOWS);
        swGl.addActionListener(e -> saveCfg());
        g.gridx = 0; g.gridy = 1; g.gridwidth = 2; g.weightx = 0; controls.add(swGl, g);

        // account row
        JPanel acc = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        acc.setOpaque(false);
        account = new JLabel(); account.setForeground(TEXT);
        loginBtn = new JButton("Войти через сайт");
        guestBtn = new JButton("Играть без лицензии");
        loginBtn.addActionListener(e -> loginViaSite());
        guestBtn.addActionListener(e -> playAsGuest());
        acc.add(account); acc.add(loginBtn); acc.add(guestBtn);
        g.gridx = 0; g.gridy = 2; g.gridwidth = 2; controls.add(acc, g);
        refreshAccount();

        center.add(controls, BorderLayout.NORTH);

        log = new JTextArea();
        log.setEditable(false);
        log.setBackground(PANEL); log.setForeground(SUB);
        log.setFont(new Font("Monospaced", Font.PLAIN, 12));
        log.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(log);
        sp.setBorder(BorderFactory.createLineBorder(PANEL));
        center.add(sp, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // bottom: status + progress + buttons
        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setOpaque(false);
        status = new JLabel("Ready"); status.setForeground(TEXT);
        bar = new JProgressBar(0, 100); bar.setStringPainted(true);
        JPanel statusRow = new JPanel(new BorderLayout(8, 0)); statusRow.setOpaque(false);
        statusRow.add(status, BorderLayout.WEST);
        statusRow.add(bar, BorderLayout.CENTER);
        bottom.add(statusRow, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
        buttons.setOpaque(false);
        openFolder = new JButton("OPEN FOLDER");
        update = new JButton("UPDATE");
        play = new JButton("PLAY");
        play.setFont(new Font("SansSerif", Font.BOLD, 16));
        openFolder.addActionListener(e -> openGameFolder());
        update.addActionListener(e -> run(true));
        play.addActionListener(e -> run(false));
        buttons.add(openFolder); buttons.add(update); buttons.add(play);
        bottom.add(buttons, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        f.setContentPane(root);
        f.setVisible(true);

        fetchVersionsAsync();
        checkUpdateAsync();
    }

    // ---------------- account ----------------
    static void refreshAccount() {
        Properties p = readProfile();
        String name = p.getProperty("name", "");
        boolean hasToken = !p.getProperty("token", "").isEmpty();
        SwingUtilities.invokeLater(() -> {
            if (hasToken) account.setText("Аккаунт: " + name + "  ✓");
            else if (!name.isEmpty()) account.setText("Гость: " + name);
            else account.setText("Не выполнен вход");
        });
    }

    static void loginViaSite() {
        setButtons(false);
        new Thread(() -> {
            try {
                appendLog("Запрашиваю код входа...");
                String start = httpPost(BACKEND + "/api/device/start", "");
                String code = extract(start, "code");
                if (code == null) throw new IOException("no code from server");
                String url = BACKEND + "/?code=" + code;
                openInBrowser(url);
                setStatus("Подтвердите вход в браузере...", 0);
                for (int i = 0; i < 90; i++) {           // poll up to ~3 min
                    Thread.sleep(2000);
                    String poll = httpPost(BACKEND + "/api/device/poll", "code=" + code);
                    if (poll.contains("\"status\":\"ok\"")) {
                        String token = extract(poll, "token"), name = extract(poll, "name");
                        boolean premium = poll.contains("\"premium\":true");
                        writeProfile(name, token);
                        refreshAccount();
                        setStatus("Вошли как " + name + (premium ? " ✓" : ""), 0);
                        appendLog("Вход выполнен: " + name + (premium ? " (premium)" : ""));
                        return;
                    }
                }
                setStatus("Время ожидания входа истекло", 0);
            } catch (Exception ex) {
                setStatus("Ошибка входа: " + ex.getMessage(), 0);
                appendLog("login error: " + ex);
            } finally { setButtons(true); }
        }, "launcher-login").start();
    }

    /** Try to open a real browser; always show a copyable link as a fallback. */
    static void openInBrowser(String url) {
        // copy to clipboard
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(url), null);
        } catch (Exception ignored) {}
        // best-effort auto-open: prefer real browsers over the default http handler
        boolean opened = false;
        String[] browsers = WINDOWS
            ? new String[]{"cmd", "/c", "start", "", url}
            : new String[]{"xdg-open", url};
        try { new ProcessBuilder(browsers).start(); opened = true; } catch (Exception ignored) {}
        appendLog("Ссылка для входа: " + url);
        final boolean auto = opened;
        SwingUtilities.invokeLater(() -> {
            JTextField field = new JTextField(url);
            field.setEditable(false);
            field.selectAll();
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.add(new JLabel("<html>Открой эту ссылку в браузере и войди.<br>"
                + "Ссылка скопирована в буфер обмена" + (auto ? " (и попытались открыть)." : ".") + "</html>"), BorderLayout.NORTH);
            panel.add(field, BorderLayout.CENTER);
            JOptionPane.showMessageDialog(null, panel, "Вход через сайт", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    static void playAsGuest() {
        String nick = JOptionPane.showInputDialog(null, "Введите ник (без лицензии):", "Гость", JOptionPane.QUESTION_MESSAGE);
        if (nick == null) return;
        nick = nick.trim();
        if (nick.isEmpty()) nick = "Player";
        writeProfile(nick, "");   // no token => free
        refreshAccount();
        setStatus("Гость: " + nick, 0);
    }

    static Properties readProfile() {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(PROFILE)) { p.load(in); } catch (IOException ignored) {}
        return p;
    }
    static void writeProfile(String name, String token) {
        Properties p = new Properties();
        p.setProperty("name", name == null ? "" : name);
        p.setProperty("token", token == null ? "" : token);
        try { Files.createDirectories(HOME); try (OutputStream o = Files.newOutputStream(PROFILE)) { p.store(o, "PACMine profile"); } }
        catch (IOException e) { appendLog("cannot save profile: " + e.getMessage()); }
    }

    static String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String httpPost(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PACMine-Launcher");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes("UTF-8")); }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); }
        return bo.toString("UTF-8");
    }

    // ---------------- actions ----------------
    static final String MAIN_LABEL = "main (последняя версия)";
    static String ref() {
        Object s = versionBox.getSelectedItem();
        if (s == null) return "main";
        String v = s.toString();
        return v.startsWith("main") ? "main" : v;
    }

    static void run(boolean updateOnly) {
        setButtons(false);
        new Thread(() -> {
            try {
                String ref = ref();
                boolean installed = Files.exists(GAMEDIR.resolve("src/com/voxel/Main.java"));
                boolean sameRef = ref.equals(cfg.getProperty("installedRef", ""));
                boolean needInstall = updateOnly || !installed || !sameRef || updateAvailable(ref);
                if (needInstall) {
                    install(ref);
                }
                if (updateOnly) {
                    setStatus("Up to date!", 100);
                } else {
                    java.util.Map<String, String> env = null;
                    if (WINDOWS && swGl.isSelected()) {
                        setStatus("Installing software OpenGL...", 90);
                        ensureMesa();
                        env = new java.util.HashMap<>();
                        env.put("GALLIUM_DRIVER", "llvmpipe");
                        env.put("LP_NUM_THREADS", "1");
                        env.put("MESA_GL_VERSION_OVERRIDE", "2.1");
                    }
                    setStatus("Launching...", 100);
                    exec(GAMEDIR, env, script("run"));
                    setStatus("Ready", 0);
                }
            } catch (Exception ex) {
                setStatus("Error: " + ex.getMessage(), 0);
                appendLog("ERROR: " + ex);
            } finally {
                setButtons(true);
                checkUpdateAsync();
            }
        }, "launcher-run").start();
    }

    static void install(String ref) throws Exception {
        setStatus("Downloading " + ref + "...", 5);
        appendLog("Downloading " + ref + " ...");
        Files.createDirectories(HOME);
        Path zip = HOME.resolve("game.zip");
        download(archiveUrl(ref), zip);
        setStatus("Extracting...", 40);
        // clean previous extraction dirs
        deleteExtracted();
        extract(zip, HOME);
        Path extracted = findExtracted();
        if (extracted != null) {
            deleteDir(GAMEDIR);
            Files.move(extracted, GAMEDIR);
        }
        Files.deleteIfExists(zip);
        setStatus("Fetching libraries...", 60);
        appendLog("Fetching LWJGL...");
        exec(GAMEDIR, null, script("get-deps"));
        setStatus("Compiling...", 80);
        appendLog("Compiling...");
        exec(GAMEDIR, null, script("build"));
        // record installed version
        String sha = latestSha(ref);
        cfg.setProperty("installedRef", ref);
        if (sha != null) cfg.setProperty("installedSha", sha);
        saveCfg();
        appendLog("Installed " + ref + (sha != null ? " @ " + sha.substring(0, 7) : ""));
    }

    static void openGameFolder() {
        try {
            Files.createDirectories(GAMEDIR);
            Desktop.getDesktop().open(GAMEDIR.toFile());
        } catch (Exception e) { appendLog("Cannot open folder: " + e.getMessage()); }
    }

    // ---------------- update / version checks ----------------
    static boolean updateAvailable(String ref) {
        if (!ref.equals(cfg.getProperty("installedRef", ""))) return true;
        String local = cfg.getProperty("installedSha", "");
        String remote = latestSha(ref);
        return remote != null && !remote.equals(local);
    }

    static void checkUpdateAsync() {
        new Thread(() -> {
            String ref = ref();
            boolean installed = Files.exists(GAMEDIR.resolve("src/com/voxel/Main.java"));
            String txt;
            if (!installed) txt = "not installed";
            else if (updateAvailable(ref)) txt = "update available";
            else txt = "up to date";
            String shown = "version: " + ref + "  •  " + txt;
            SwingUtilities.invokeLater(() -> versionInfo.setText(shown));
        }, "launcher-check").start();
    }

    static void fetchVersionsAsync() {
        new Thread(() -> {
            List<String> tags = fetchReleaseTags();
            SwingUtilities.invokeLater(() -> {
                String sel = cfg.getProperty("ref", "main");
                versionBox.removeAllItems();
                versionBox.addItem(MAIN_LABEL);
                for (String t : tags) versionBox.addItem(t);
                versionBox.setSelectedItem(sel.equals("main") ? MAIN_LABEL : sel);
            });
        }, "launcher-versions").start();
    }

    static List<String> fetchReleaseTags() {
        List<String> out = new ArrayList<>();
        try {
            String json = httpGet("https://api.github.com/repos/" + REPO + "/releases?per_page=20");
            Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            while (m.find()) out.add(m.group(1));
        } catch (Exception e) { appendLog("Could not fetch versions: " + e.getMessage()); }
        return out;
    }

    static String latestSha(String ref) {
        try {
            String json = httpGet("https://api.github.com/repos/" + REPO + "/commits/" + ref);
            Matcher m = Pattern.compile("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"").matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    static String archiveUrl(String ref) {
        return ref.equals("main")
            ? "https://github.com/" + REPO + "/archive/refs/heads/main.zip"
            : "https://github.com/" + REPO + "/archive/refs/tags/" + ref + ".zip";
    }

    // ---------------- helpers ----------------
    static void ensureMesa() throws IOException {
        Path binDir = Paths.get(System.getProperty("java.home"), "bin");
        if (Files.exists(binDir.resolve("opengl32.dll")) && Files.exists(binDir.resolve("libgallium_wgl.dll"))) return;
        Path zip = HOME.resolve("mesa-win.zip");
        download(MESA_ZIP, zip);
        try { extract(zip, binDir); }
        catch (IOException e) {
            extract(zip, GAMEDIR);
            throw new IOException("No access to the JDK; placed DLLs in the game folder. Run as administrator if OpenGL still fails.");
        } finally { Files.deleteIfExists(zip); }
    }

    static String httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PACMine-Launcher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
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
                if (!out.startsWith(destDir)) continue;
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

    static Path findExtracted() throws IOException {
        try (var s = Files.list(HOME)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("PACMine-"))
                    .findFirst().orElse(null);
        }
    }
    static void deleteExtracted() throws IOException {
        try (var s = Files.list(HOME)) {
            for (Path p : (Iterable<Path>) s::iterator)
                if (Files.isDirectory(p) && p.getFileName().toString().startsWith("PACMine-")) deleteDir(p);
        }
    }

    static void exec(Path dir, java.util.Map<String, String> env, String... cmd)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        if (env != null) pb.environment().putAll(env);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) appendLog(line);
        }
        int code = p.waitFor();
        if (code != 0) throw new IOException(cmd[cmd.length - 1] + " failed (exit " + code + ")");
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }

    static String[] script(String name) {
        return WINDOWS ? new String[]{"cmd", "/c", name + ".bat"} : new String[]{"bash", name + ".sh"};
    }

    // ---------------- config ----------------
    static void loadCfg() {
        try (InputStream in = Files.newInputStream(CFG)) { cfg.load(in); } catch (IOException ignored) {}
    }
    static void saveCfg() {
        cfg.setProperty("ref", ref());
        cfg.setProperty("vm", String.valueOf(swGl != null && swGl.isSelected()));
        try { Files.createDirectories(HOME); try (OutputStream o = Files.newOutputStream(CFG)) { cfg.store(o, "PACMine launcher"); } }
        catch (IOException ignored) {}
    }

    static void setStatus(String s, int pct) {
        SwingUtilities.invokeLater(() -> { status.setText(s); bar.setValue(pct); });
    }
    static void appendLog(String s) {
        SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); });
    }
    static void setButtons(boolean on) {
        SwingUtilities.invokeLater(() -> { play.setEnabled(on); update.setEnabled(on); versionBox.setEnabled(on); });
    }
}
