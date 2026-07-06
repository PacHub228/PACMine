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

    static final Color BG = new Color(0x171A21), PANEL = new Color(0x21252F),
                       PANEL_HI = new Color(0x2B3040), BORDER = new Color(0x333A4A),
                       ACCENT = new Color(0xC6E86B), ACCENT_HI = new Color(0xDBF791),
                       TEXT = new Color(0xE8EAEF), SUB = new Color(0x8B93A3);
    static final Font UI = new Font("SansSerif", Font.PLAIN, 13);

    static JLabel status, versionInfo, account;
    static JProgressBar bar;
    static JButton play, update, openFolder, loginBtn, guestBtn;
    static JComboBox<String> versionBox;
    static JCheckBox swGl, devBox;
    static JTextArea log, news;
    static final Properties cfg = new Properties();

    public static void main(String[] args) {
        loadCfg();
        SwingUtilities.invokeLater(LauncherMain::buildUi);
    }

    // ---------------- UI ----------------

    /** Flat rounded button. accent=true gives it the lime "PLAY" style. */
    static class FlatButton extends JButton {
        final boolean accent;
        boolean hover;
        FlatButton(String text, boolean accent) {
            super(text);
            this.accent = accent;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setFont(accent ? new Font("SansSerif", Font.BOLD, 16) : UI.deriveFont(Font.BOLD));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
            });
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            boolean press = getModel().isPressed();
            if (accent) {
                Color top = press ? ACCENT : hover ? ACCENT_HI : ACCENT;
                Color bot = press ? new Color(0x9DBF48) : new Color(0xA9CE54);
                if (!isEnabled()) { top = PANEL_HI; bot = PANEL_HI; }
                g.setPaint(new GradientPaint(0, 0, top, 0, h, bot));
                g.fillRoundRect(0, 0, w, h, 14, 14);
                if (isEnabled()) {
                    g.setColor(new Color(255, 255, 255, 70));
                    g.drawLine(8, 2, w - 8, 2);
                }
            } else {
                g.setColor(!isEnabled() ? PANEL : press ? BG : hover ? PANEL_HI : PANEL);
                g.fillRoundRect(0, 0, w, h, 12, 12);
                g.setColor(hover && isEnabled() ? SUB : BORDER);
                g.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            }
            g.dispose();
            setForeground(!isEnabled() ? SUB : accent ? new Color(0x1E2409) : TEXT);
            super.paintComponent(g0);
        }
    }

    /** Rounded card panel used for grouping sections. */
    static class Card extends JPanel {
        Card(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PANEL);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g.setColor(BORDER);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Pixel-art voxel landscape banner, painted procedurally (no assets needed). */
    static class Hero extends JPanel {
        Hero() { setOpaque(false); setLayout(new BorderLayout()); }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), h = getHeight();
            Shape clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 16, 16);
            g.setClip(clip);
            // night-dusk sky
            g.setPaint(new GradientPaint(0, 0, new Color(0x232B4A), 0, h, new Color(0x36415F)));
            g.fillRect(0, 0, w, h);
            // stars (deterministic)
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < 60; i++) {
                int sx = r.nextInt(Math.max(w, 1)), sy = r.nextInt(Math.max(h * 2 / 3, 1));
                g.setColor(new Color(255, 255, 255, 40 + r.nextInt(120)));
                int s = r.nextInt(10) == 0 ? 3 : 2;
                g.fillRect(sx, sy, s, s);
            }
            // pixel moon (square, blocky)
            int ms = 26, mx = w - 90, my = 24;
            g.setColor(new Color(255, 255, 230, 25));
            g.fillRect(mx - 8, my - 8, ms + 16, ms + 16);
            g.setColor(new Color(0xEDEFD8));
            g.fillRect(mx, my, ms, ms);
            g.setColor(new Color(0xC9CCB2));
            g.fillRect(mx + 14, my + 6, 7, 7);
            g.fillRect(mx + 5, my + 16, 6, 6);
            // blocky terrain: far hills then near hills
            int b = 12;
            paintHills(g, w, h, b, 0.48, new Color(0x3E5C43), new Color(0x35434E), new Color(0x2C3742), 40);
            paintHills(g, w, h, b, 0.70, new Color(0x6FA84E), new Color(0x5B4433), new Color(0x47505F), 0);
            // pixel trees on the near layer
            r = new java.util.Random(3);
            for (int tx = 60; tx < w - 60; tx += 130 + r.nextInt(90)) {
                int ground = groundY(tx, w, h, 0.70);
                paintTree(g, tx, ground, b);
            }
            // dark gradient at the bottom so text stays readable
            g.setPaint(new GradientPaint(0, h - 70, new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 140)));
            g.fillRect(0, h - 70, w, 70);
            // title with hard pixel shadow
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font tf = new Font("Monospaced", Font.BOLD, 46);
            g.setFont(tf);
            g.setColor(new Color(0, 0, 0, 160));
            g.drawString("PACMINE", 27, h - 21);
            g.setColor(ACCENT);
            g.drawString("PACMINE", 24, h - 24);
            g.setFont(UI);
            g.setColor(new Color(255, 255, 255, 190));
            g.drawString("voxel sandbox • survival • multiplayer", 26, h - 8);
            // frame border
            g.setColor(BORDER);
            g.draw(new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, 16, 16));
            g.dispose();
        }
        static int groundY(int x, int w, int h, double base) {
            double t = x / (double) w;
            double y = base + 0.10 * Math.sin(t * 9) + 0.06 * Math.sin(t * 23 + 1.7) + 0.03 * Math.sin(t * 51);
            return (int) (y * h);
        }
        static void paintHills(Graphics2D g, int w, int h, int b, double base, Color grass, Color dirt, Color stone, int dim) {
            for (int x = 0; x < w; x += b) {
                int gy = (groundY(x, w, h, base) / b) * b;
                for (int y = gy; y < h; y += b) {
                    Color c = y == gy ? grass : y < gy + 3 * b ? dirt : stone;
                    if (dim > 0) c = new Color(Math.max(c.getRed() - dim, 0), Math.max(c.getGreen() - dim, 0), Math.max(c.getBlue() - dim, 0));
                    // subtle per-block shade variation like the game's textures
                    int v = ((x / b) * 31 + (y / b) * 17) % 3 * 6 - 6;
                    g.setColor(new Color(clamp(c.getRed() + v), clamp(c.getGreen() + v), clamp(c.getBlue() + v)));
                    g.fillRect(x, y, b, b);
                }
            }
        }
        static void paintTree(Graphics2D g, int x, int ground, int b) {
            x = (x / b) * b; ground = (ground / b) * b;
            g.setColor(new Color(0x6B4B2A));
            g.fillRect(x, ground - 3 * b, b, 3 * b);
            g.setColor(new Color(0x4F7D3A));
            g.fillRect(x - b, ground - 5 * b, 3 * b, 2 * b);
            g.fillRect(x, ground - 6 * b, b, b);
            g.setColor(new Color(0x5C9144));
            g.fillRect(x - b, ground - 5 * b, b, b);
        }
        static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    }

    /** Dark scrollbar matching the theme. */
    static class DarkScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { thumbColor = BORDER; trackColor = PANEL; }
        @Override protected JButton createDecreaseButton(int o) { return zeroButton(); }
        @Override protected JButton createIncreaseButton(int o) { return zeroButton(); }
        static JButton zeroButton() { JButton b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(isDragging ? SUB : BORDER);
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g.dispose();
        }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(new Color(0x12141A));
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    static JCheckBox checkBox(String text) {
        JCheckBox cb = new JCheckBox(text);
        cb.setOpaque(false); cb.setForeground(TEXT); cb.setFont(UI);
        cb.setFocusPainted(false);
        cb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        cb.setIconTextGap(8);
        cb.setIcon(new Icon() {
            public int getIconWidth() { return 17; } public int getIconHeight() { return 17; }
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean sel = ((JCheckBox) c).isSelected();
                g.setColor(sel ? ACCENT : PANEL_HI);
                g.fillRoundRect(x, y, 17, 17, 6, 6);
                g.setColor(sel ? ACCENT : BORDER);
                g.drawRoundRect(x, y, 16, 16, 6, 6);
                if (sel) {
                    g.setColor(new Color(0x1E2409));
                    g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.drawPolyline(new int[]{x + 4, x + 7, x + 13}, new int[]{y + 9, y + 12, y + 5}, 3);
                }
                g.dispose();
            }
        });
        return cb;
    }

    static void styleCombo(JComboBox<String> box) {
        box.setFont(UI);
        box.setBackground(PANEL_HI); box.setForeground(TEXT);
        box.setFocusable(false);
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        box.setUI(new javax.swing.plaf.basic.BasicComboBoxUI() {
            @Override protected JButton createArrowButton() {
                JButton b = new JButton() {
                    @Override protected void paintComponent(Graphics g0) {
                        Graphics2D g = (Graphics2D) g0.create();
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(PANEL_HI); g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(SUB);
                        int cx = getWidth() / 2, cy = getHeight() / 2;
                        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g.drawPolyline(new int[]{cx - 4, cx, cx + 4}, new int[]{cy - 2, cy + 3, cy - 2}, 3);
                        g.dispose();
                    }
                };
                b.setBorder(BorderFactory.createEmptyBorder());
                b.setContentAreaFilled(false);
                return b;
            }
            @Override public void paintCurrentValueBackground(Graphics g, Rectangle b, boolean focus) {
                g.setColor(PANEL_HI); g.fillRect(b.x, b.y, b.width, b.height);
            }
        });
        box.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(4, 8, 4, 4)));
        box.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                setBackground(sel ? BORDER : PANEL_HI); setForeground(TEXT);
                setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                return this;
            }
        });
    }

    /** Frameless-window title bar: app name, drag-to-move, minimize/close. */
    static JPanel titleBar(JFrame f) {
        JPanel tb = new JPanel(new BorderLayout());
        tb.setOpaque(false);
        tb.setBorder(BorderFactory.createEmptyBorder(10, 16, 6, 10));
        JLabel t = new JLabel("PACMINE LAUNCHER");
        t.setFont(new Font("Monospaced", Font.BOLD, 12));
        t.setForeground(SUB);
        tb.add(t, BorderLayout.WEST);

        JPanel wb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        wb.setOpaque(false);
        wb.add(windowButton("min", f)); wb.add(windowButton("close", f));
        tb.add(wb, BorderLayout.EAST);

        final Point[] drag = new Point[1];
        tb.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) { drag[0] = e.getPoint(); }
        });
        tb.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                if (drag[0] == null) return;
                Point p = f.getLocation();
                f.setLocation(p.x + e.getX() - drag[0].x, p.y + e.getY() - drag[0].y);
            }
        });
        return tb;
    }

    static JButton windowButton(String kind, JFrame f) {
        JButton b = new JButton() {
            boolean hover;
            { addMouseListener(new java.awt.event.MouseAdapter() {
                  @Override public void mouseEntered(java.awt.event.MouseEvent e) { hover = true; repaint(); }
                  @Override public void mouseExited(java.awt.event.MouseEvent e)  { hover = false; repaint(); }
              }); }
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hover) {
                    g.setColor(kind.equals("close") ? new Color(0xC44A4A) : PANEL_HI);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                g.setColor(hover ? TEXT : SUB);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2, cy = getHeight() / 2;
                if (kind.equals("close")) { g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4); g.drawLine(cx + 4, cy - 4, cx - 4, cy + 4); }
                else g.drawLine(cx - 4, cy + 3, cx + 4, cy + 3);
                g.dispose();
            }
        };
        b.setPreferredSize(new Dimension(30, 24));
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> { if (kind.equals("close")) System.exit(0); else f.setState(Frame.ICONIFIED); });
        return b;
    }

    static void buildUi() {
        JFrame f = createFrame();
        f.setVisible(true);
        fetchVersionsAsync();
        checkUpdateAsync();
        fetchNewsAsync();
    }

    static JFrame createFrame() {
        JFrame f = new JFrame("PACMine Launcher");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setUndecorated(true);
        f.setSize(980, 560);
        f.setLocationRelativeTo(null);
        f.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                f.setShape(new java.awt.geom.RoundRectangle2D.Float(0, 0, f.getWidth(), f.getHeight(), 18, 18));
            }
        });

        JPanel root = new JPanel(new BorderLayout(0, 0)) {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setPaint(new GradientPaint(0, 0, BG, 0, getHeight(), new Color(0x12141A)));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.dispose();
            }
        };
        root.setBorder(BorderFactory.createLineBorder(BORDER));

        // top: custom titlebar + hero banner
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(titleBar(f), BorderLayout.NORTH);

        Hero hero = new Hero();
        hero.setPreferredSize(new Dimension(0, 170));
        hero.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 14));
        versionInfo = new JLabel("проверка версии...");
        versionInfo.setFont(UI);
        versionInfo.setForeground(new Color(255, 255, 255, 210));
        versionInfo.setHorizontalAlignment(SwingConstants.RIGHT);
        versionInfo.setVerticalAlignment(SwingConstants.BOTTOM);
        hero.add(versionInfo, BorderLayout.SOUTH);
        JPanel heroWrap = new JPanel(new BorderLayout());
        heroWrap.setOpaque(false);
        heroWrap.setBorder(BorderFactory.createEmptyBorder(2, 16, 0, 16));
        heroWrap.add(hero, BorderLayout.CENTER);
        top.add(heroWrap, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);

        // center: version + account cards side by side, then log
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);
        center.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

        JPanel cards = new JPanel(new GridLayout(1, 2, 12, 0));
        cards.setOpaque(false);

        Card verCard = new Card(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 0, 3, 0); g.fill = GridBagConstraints.HORIZONTAL;
        g.gridx = 0; g.gridy = 0; g.weightx = 1; g.anchor = GridBagConstraints.WEST;

        JLabel vlab = new JLabel("ВЕРСИЯ ИГРЫ");
        vlab.setForeground(SUB); vlab.setFont(new Font("SansSerif", Font.BOLD, 11));
        verCard.add(vlab, g);
        versionBox = new JComboBox<>(new String[]{MAIN_LABEL});
        styleCombo(versionBox);
        versionBox.setSelectedItem(MAIN_LABEL);
        versionBox.addActionListener(e -> { saveCfg(); checkUpdateAsync(); });
        g.gridy = 1; verCard.add(versionBox, g);

        devBox = checkBox("Dev-сборка (последний коммит main)");
        devBox.setSelected(Boolean.parseBoolean(cfg.getProperty("dev", "false")));
        devBox.addActionListener(e -> {
            versionBox.setEnabled(!devBox.isSelected());
            saveCfg(); checkUpdateAsync();
        });
        versionBox.setEnabled(!devBox.isSelected());
        g.gridy = 2; verCard.add(devBox, g);

        swGl = checkBox("Software OpenGL (для ВМ)");
        swGl.setSelected(Boolean.parseBoolean(cfg.getProperty("vm", "false")));
        swGl.setVisible(WINDOWS);
        swGl.addActionListener(e -> saveCfg());
        g.gridy = 3; verCard.add(swGl, g);
        cards.add(verCard);

        Card accCard = new Card(new GridBagLayout());
        GridBagConstraints a = new GridBagConstraints();
        a.insets = new Insets(3, 0, 3, 0); a.fill = GridBagConstraints.HORIZONTAL;
        a.gridx = 0; a.gridy = 0; a.weightx = 1; a.gridwidth = 2;
        JLabel alab = new JLabel("АККАУНТ");
        alab.setForeground(SUB); alab.setFont(new Font("SansSerif", Font.BOLD, 11));
        accCard.add(alab, a);
        account = new JLabel(); account.setForeground(TEXT); account.setFont(UI.deriveFont(Font.BOLD, 14f));
        a.gridy = 1; accCard.add(account, a);
        loginBtn = new FlatButton("Войти через сайт", false);
        guestBtn = new FlatButton("Без лицензии", false);
        loginBtn.setFont(UI); guestBtn.setFont(UI);
        loginBtn.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        guestBtn.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
        loginBtn.addActionListener(e -> loginViaSite());
        guestBtn.addActionListener(e -> playAsGuest());
        a.gridy = 2; a.gridwidth = 1; a.insets = new Insets(6, 0, 0, 6); accCard.add(loginBtn, a);
        a.gridx = 1; a.insets = new Insets(6, 0, 0, 0); accCard.add(guestBtn, a);
        cards.add(accCard);
        refreshAccount();

        center.add(cards, BorderLayout.NORTH);

        // log lives below the cards, as before
        log = darkArea(new Font("Monospaced", Font.PLAIN, 12));
        JPanel logCol = new JPanel(new BorderLayout(0, 4));
        logCol.setOpaque(false);
        logCol.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JLabel logLab = new JLabel("ЛОГ");
        logLab.setForeground(SUB); logLab.setFont(new Font("SansSerif", Font.BOLD, 11));
        logCol.add(logLab, BorderLayout.NORTH);
        logCol.add(darkScroll(log), BorderLayout.CENTER);
        center.add(logCol, BorderLayout.CENTER);

        // news column on the LEFT of everything
        news = darkArea(new Font("SansSerif", Font.PLAIN, 13));
        news.setText("Загружаю новости...");
        news.setLineWrap(true); news.setWrapStyleWord(true);
        JPanel newsCol = new JPanel(new BorderLayout(0, 4));
        newsCol.setOpaque(false);
        newsCol.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 0));
        newsCol.setPreferredSize(new Dimension(300, 0));
        JLabel newsLab = new JLabel("НОВОСТИ");
        newsLab.setForeground(SUB); newsLab.setFont(new Font("SansSerif", Font.BOLD, 11));
        newsCol.add(newsLab, BorderLayout.NORTH);
        newsCol.add(darkScroll(news), BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(newsCol, BorderLayout.WEST);
        body.add(center, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        // bottom bar: folder/update, status + progress, big PLAY
        JPanel bottom = new JPanel(new BorderLayout(14, 0));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));

        JPanel leftBtns = new JPanel(new GridLayout(1, 2, 8, 0));
        leftBtns.setOpaque(false);
        openFolder = new FlatButton("Папка", false);
        update = new FlatButton("Обновить", false);
        openFolder.addActionListener(e -> openGameFolder());
        update.addActionListener(e -> run(true));
        leftBtns.add(openFolder); leftBtns.add(update);
        bottom.add(leftBtns, BorderLayout.WEST);

        JPanel mid = new JPanel();
        mid.setOpaque(false);
        mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
        status = new JLabel("Готово"); status.setForeground(SUB); status.setFont(UI);
        status.setAlignmentX(0f);
        bar = new JProgressBar(0, 100);
        bar.setBorderPainted(false);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        bar.setPreferredSize(new Dimension(100, 6));
        bar.setAlignmentX(0f);
        bar.setUI(new javax.swing.plaf.basic.BasicProgressBarUI() {
            @Override protected void paintDeterminate(Graphics g0, JComponent c) {
                Graphics2D g2 = (Graphics2D) g0.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = c.getWidth(), h = c.getHeight();
                g2.setColor(PANEL_HI);
                g2.fillRoundRect(0, 0, w, h, h, h);
                int fill = (int) (w * progressBar.getPercentComplete());
                if (fill > 0) { g2.setColor(ACCENT); g2.fillRoundRect(0, 0, Math.max(fill, h), h, h, h); }
                g2.dispose();
            }
        });
        mid.add(Box.createVerticalGlue());
        mid.add(status);
        mid.add(Box.createVerticalStrut(6));
        mid.add(bar);
        mid.add(Box.createVerticalGlue());
        bottom.add(mid, BorderLayout.CENTER);

        play = new FlatButton("ИГРАТЬ", true);
        play.setPreferredSize(new Dimension(190, 50));
        play.addActionListener(e -> run(false));
        bottom.add(play, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        f.setContentPane(root);
        return f;
    }

    static JTextArea darkArea(Font font) {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setBackground(new Color(0x12141A)); a.setForeground(SUB);
        a.setCaretColor(TEXT);
        a.setFont(font);
        a.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        return a;
    }

    static JScrollPane darkScroll(JTextArea a) {
        JScrollPane sp = new JScrollPane(a);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        sp.getVerticalScrollBar().setUI(new DarkScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getVerticalScrollBar().setBackground(new Color(0x12141A));
        sp.getViewport().setBackground(new Color(0x12141A));
        return sp;
    }

    /** Fetch the latest release changelogs from GitHub into the news tab. */
    static void fetchNewsAsync() {
        new Thread(() -> {
            try {
                String json = httpGet("https://api.github.com/repos/" + REPO + "/releases?per_page=4");
                StringBuilder sb = new StringBuilder();
                int pos = 0;
                while (true) {
                    int t = json.indexOf("\"tag_name\"", pos);
                    if (t < 0) break;
                    int next = json.indexOf("\"tag_name\"", t + 10);
                    int end = next < 0 ? json.length() : next;
                    String tag = jsonString(json, t);
                    int nm = json.indexOf("\"name\"", t);
                    String name = (nm >= 0 && nm < end) ? jsonString(json, nm) : "";
                    int bd = json.indexOf("\"body\"", t);
                    String body = (bd >= 0 && bd < end) ? jsonString(json, bd) : "";
                    sb.append("=== ").append(name.isEmpty() ? tag : name).append(" ===\n");
                    sb.append(body.replaceAll("(?m)^#+\\s*", "").replace("**", "").replace("`", ""));
                    sb.append("\n\n");
                    pos = end;
                }
                String text = sb.length() == 0 ? "Новостей пока нет." : sb.toString();
                SwingUtilities.invokeLater(() -> { news.setText(text); news.setCaretPosition(0); });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> news.setText("Не удалось загрузить новости: " + e.getMessage()));
            }
        }, "launcher-news").start();
    }

    /** Read the JSON string value that follows the key at `keyIdx` ("key":"..."),
     *  unescaping as we go. No regex — immune to catastrophic backtracking. */
    static String jsonString(String json, int keyIdx) {
        int i = json.indexOf(':', keyIdx);
        if (i < 0) return "";
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";   // null or non-string
        StringBuilder out = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') break;
            if (ch == '\\' && i + 1 < json.length()) {
                char e = json.charAt(++i);
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 'r': break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: out.append(e);
                }
            } else out.append(ch);
        }
        return out.toString();
    }

    // ---------------- account ----------------
    static void refreshAccount() {
        Properties p = readProfile();
        String name = p.getProperty("name", "");
        String token = p.getProperty("token", "");
        SwingUtilities.invokeLater(() -> {
            if (!token.isEmpty()) account.setText("Аккаунт: " + name);
            else if (!name.isEmpty()) account.setText("Гость: " + name);
            else account.setText("Не выполнен вход");
        });
        if (token.isEmpty()) return;
        new Thread(() -> {          // live premium check against the backend
            try {
                String resp = httpGetAny(BACKEND + "/api/me?token=" + java.net.URLEncoder.encode(token, "UTF-8"));
                if (resp.contains("bad token")) {
                    SwingUtilities.invokeLater(() -> account.setText("<html>Аккаунт: " + name
                        + "  <font color='#e0a050'>сессия истекла — войди заново</font></html>"));
                    return;
                }
                boolean prem = resp.contains("\"premium\":true");
                Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"").matcher(resp);
                String nm = m.find() ? m.group(1) : name;
                SwingUtilities.invokeLater(() -> account.setText("<html>Аккаунт: " + nm
                    + (prem ? "  <font color='#c6e86b'><b>✓ PREMIUM</b></font>"
                            : "  <font color='#8b93a3'>FREE</font>") + "</html>"));
            } catch (Exception ignored) {}
        }, "launcher-premium").start();
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
        if (devBox != null && devBox.isSelected()) return "main";   // dev: latest commit
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
                String sel = cfg.getProperty("ref", "");
                versionBox.removeAllItems();
                for (String t : tags) versionBox.addItem(t);
                if (tags.isEmpty()) versionBox.addItem(MAIN_LABEL);
                if (!sel.isEmpty() && !sel.equals("main")) versionBox.setSelectedItem(sel);
                else if (!tags.isEmpty()) versionBox.setSelectedIndex(0); // newest release
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

    /** GET that also returns error bodies (4xx) instead of throwing. */
    static String httpGetAny(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PACMine-Launcher");
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); }
        return bo.toString("UTF-8");
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
        cfg.setProperty("dev", String.valueOf(devBox != null && devBox.isSelected()));
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
