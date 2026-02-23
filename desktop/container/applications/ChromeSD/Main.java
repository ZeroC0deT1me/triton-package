import shared.AbstractModule;
import shared.BottomBarMenuProvider;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

import echelon.desktop.DesktopModule;
import echelon.desktop.components.BottomBarPanel;

import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

/**
 * Chrome Browser Module — JCEF-powered embedded Chromium browser.
 *
 * JCEF jars and natives must be on Echelon's MAIN classpath,
 * not in ChromeSD/lib/. See setup instructions.
 */
public class Main extends AbstractModule implements BottomBarMenuProvider {

    private JFrame frame;

    private CefApp    cefApp;
    private CefClient cefClient;

    private final List<TabInfo> tabs = new ArrayList<>();
    private JTabbedPane tabbedPane;
    private int tabCounter = 0;

    private JTextField urlField;
    private JButton    backButton;
    private JButton    forwardButton;
    private JButton    refreshButton;
    private JButton    goButton;
    private JLabel     statusLabel;

    private BookmarksBar bookmarksBar;

    private static final String HOME_URL = "https://www.google.com";

    private echelon.ui.snap.autofill.AutofillManager autofillManager;

    // Context menu command IDs (must be > MENU_ID_USER_FIRST = 26500)
    private static final int MENU_OPEN_NEW_TAB      = 26501;
    private static final int MENU_COPY_LINK          = 26502;
    private static final int MENU_COPY_TEXT           = 26503;
    private static final int MENU_INSTANCE_NEW        = 26510;
    private static final int MENU_INSTANCE_INCOGNITO  = 26511;
    private static final int MENU_SEARCH_GOOGLE       = 26520;
    private static final int MENU_RUN_SCRIPT          = 26521;
    private static final int MENU_ADD_BOOKMARK        = 26522;
    private static final int MENU_INSPECT             = 26530;
    private static final int MENU_BACK                = 26540;
    private static final int MENU_FORWARD             = 26541;
    private static final int MENU_RELOAD              = 26542;

    private static class TabInfo {
        final String     id;
        final CefBrowser browser;
        final Component  uiComponent;
        String           title;
        String           url;
        final boolean    incognito;

        TabInfo(String id, CefBrowser browser, boolean incognito) {
            this.id          = id;
            this.browser     = browser;
            this.uiComponent = browser.getUIComponent();
            this.title       = "New Tab";
            this.url         = "";
            this.incognito   = incognito;
        }
    }

    /** Data holder for bookmark tree nodes in the SnapPanel. */
    static class BookmarkNodeData {
        final String title;
        final String url;
        final boolean isFolder;

        BookmarkNodeData(String title, String url, boolean isFolder) {
            this.title = title;
            this.url = url;
            this.isFolder = isFolder;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public Main() {
        initCef();

        frame = echelon.ui.WindowFactory.create("Chrome Browser", true);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setSize(1024, 720);
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) { close(); }
        });

        buildUI();
        setupSnapPanel();
        setupAutofill();
        openNewTab(false);
    }

    private void initCef() {
        // CefApp is a JVM-wide singleton — reuse if already running
        CefApp.CefAppState state = CefApp.getState();

        if (state == CefApp.CefAppState.INITIALIZED) {
            cefApp = CefApp.getInstance();
        } else {
            CefSettings settings = new CefSettings();
            settings.windowless_rendering_enabled = false;
            settings.cache_path = getCachePath();
            cefApp = CefApp.getInstance(settings);
        }

        cefClient = cefApp.createClient();

        cefClient.addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser browser, String title) {
                // ── Autofill message channel ──
                // JS sets document.title to "__ECHELON_AF__:{json}" to send messages
                if (title != null && title.startsWith("__ECHELON_AF__:")) {
                    String json = title.substring("__ECHELON_AF__:".length());
                    System.out.println("[Autofill] Received via title: " + json);
                    SwingUtilities.invokeLater(() -> {
                        if (autofillManager == null) return;
                        try {
                            Component uiComp = browser.getUIComponent();
                            Point screenPos = uiComp.getLocationOnScreen();
                            autofillManager.handleMessage(json, browser,
                                    screenPos.x, screenPos.y);
                        } catch (Exception e) {
                            System.err.println("[Autofill] Error: " + e.getMessage());
                        }
                    });
                    return; // Don't update tab title with the AF message
                }

                SwingUtilities.invokeLater(() -> {
                    TabInfo tab = findTab(browser);
                    if (tab != null) {
                        tab.title = title;
                        int idx = tabs.indexOf(tab);
                        if (idx >= 0 && idx < tabbedPane.getTabCount()) {
                            String prefix = tab.incognito ? "\uD83D\uDD76 " : "";
                            tabbedPane.setTitleAt(idx, prefix + truncate(title, 25));
                            tabbedPane.setToolTipTextAt(idx, title);
                        }
                        if (tab == getActiveTab()) {
                            frame.setTitle("Chrome Browser \u2014 " + title);
                        }
                    }
                });
            }

            @Override
            public void onAddressChange(CefBrowser browser, CefFrame fr, String url) {
                SwingUtilities.invokeLater(() -> {
                    TabInfo tab = findTab(browser);
                    if (tab != null) {
                        tab.url = url;
                        if (tab == getActiveTab()) urlField.setText(url);
                    }
                });
            }

            @Override
            public void onStatusMessage(CefBrowser browser, String value) {
                SwingUtilities.invokeLater(() -> {
                    if (findTab(browser) == getActiveTab())
                        statusLabel.setText(value != null ? value : " ");
                });
            }
        });

        cefClient.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadingStateChange(CefBrowser browser,
                                             boolean isLoading, boolean canGoBack, boolean canGoForward) {
                SwingUtilities.invokeLater(() -> {
                    if (findTab(browser) == getActiveTab()) {
                        backButton.setEnabled(canGoBack);
                        forwardButton.setEnabled(canGoForward);
                        refreshButton.setText(isLoading ? "\u2715" : "\u27F3");
                        refreshButton.setToolTipText(isLoading ? "Stop" : "Refresh");
                    }
                });
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame cefFrame, int httpStatusCode) {
                // Inject autofill detection JS into every page on main frame load
                if (cefFrame != null && cefFrame.isMain()) {
                    System.out.println("[Autofill] Injecting JS into: " + browser.getURL());
                    browser.executeJavaScript(
                            echelon.ui.snap.autofill.AutofillManager.getInjectionJS(),
                            "echelon://autofill", 0);
                }
            }
        });

        // ── Custom right-click context menu ──
        cefClient.addContextMenuHandler(new CefContextMenuHandler() {

            @Override
            public void onBeforeContextMenu(CefBrowser browser, CefFrame cefFrame,
                                            CefContextMenuParams params, CefMenuModel model) {
                // Clear the default menu entirely
                model.clear();

                String linkUrl = params.getLinkUrl();
                String selText = params.getSelectionText();
                boolean hasLink = linkUrl != null && !linkUrl.isEmpty();
                boolean hasSelection = selText != null && !selText.trim().isEmpty();

                // ── Link actions ──
                if (hasLink) {
                    model.addItem(MENU_OPEN_NEW_TAB, "Open Link in New Tab");
                    model.addItem(MENU_COPY_LINK, "Copy Link Address");

                    // "Open as Instance" submenu
                    CefMenuModel instanceMenu = model.addSubMenu(26509, "Open as Instance");
                    instanceMenu.addItem(MENU_INSTANCE_NEW, "New Window");
                    instanceMenu.addItem(MENU_INSTANCE_INCOGNITO, "New Incognito Window");

                    model.addSeparator();
                }

                // ── Selection actions ──
                if (hasSelection) {
                    model.addItem(MENU_COPY_TEXT, "Copy");
                    String trimmed = selText.trim();
                    if (trimmed.length() > 30) trimmed = trimmed.substring(0, 30) + "\u2026";
                    model.addItem(MENU_SEARCH_GOOGLE, "Search Google for \"" + trimmed + "\"");
                    model.addSeparator();
                }

                // ── Navigation ──
                model.addItem(MENU_BACK, "Back");
                model.setEnabled(MENU_BACK, browser.canGoBack());
                model.addItem(MENU_FORWARD, "Forward");
                model.setEnabled(MENU_FORWARD, browser.canGoForward());
                model.addItem(MENU_RELOAD, "Reload");
                model.addSeparator();

                // ── Echelon actions ──
                model.addItem(MENU_RUN_SCRIPT, "Run Script on This Page");
                model.addItem(MENU_ADD_BOOKMARK, "Add to Bookmarks");
                model.addSeparator();

                // ── Dev tools ──
                model.addItem(MENU_INSPECT, "Inspect Element");
            }

            @Override
            public boolean onContextMenuCommand(CefBrowser browser, CefFrame cefFrame,
                                                CefContextMenuParams params, int commandId, int eventFlags) {
                String linkUrl = params.getLinkUrl();
                String selText = params.getSelectionText();
                String pageUrl = params.getPageUrl();

                switch (commandId) {

                    case MENU_OPEN_NEW_TAB:
                        if (linkUrl != null) {
                            SwingUtilities.invokeLater(() -> {
                                TabInfo newTab = createTab(false);
                                newTab.browser.loadURL(linkUrl);
                            });
                        }
                        return true;

                    case MENU_COPY_LINK:
                        if (linkUrl != null) {
                            copyToClipboard(linkUrl);
                        }
                        return true;

                    case MENU_COPY_TEXT:
                        if (selText != null) {
                            copyToClipboard(selText);
                        }
                        return true;

                    case MENU_INSTANCE_NEW:
                        if (linkUrl != null) {
                            SwingUtilities.invokeLater(() -> openInNewInstance(linkUrl, false));
                        }
                        return true;

                    case MENU_INSTANCE_INCOGNITO:
                        if (linkUrl != null) {
                            SwingUtilities.invokeLater(() -> openInNewInstance(linkUrl, true));
                        }
                        return true;

                    case MENU_SEARCH_GOOGLE:
                        if (selText != null && !selText.trim().isEmpty()) {
                            String query = selText.trim();
                            try {
                                query = java.net.URLEncoder.encode(query, "UTF-8");
                            } catch (Exception ignored) {}
                            String searchUrl = "https://www.google.com/search?q=" + query;
                            String finalUrl = searchUrl;
                            SwingUtilities.invokeLater(() -> {
                                TabInfo newTab = createTab(false);
                                newTab.browser.loadURL(finalUrl);
                            });
                        }
                        return true;

                    case MENU_RUN_SCRIPT:
                        SwingUtilities.invokeLater(() -> showScriptPicker(browser));
                        return true;

                    case MENU_ADD_BOOKMARK:
                        SwingUtilities.invokeLater(() -> {
                            TabInfo tab = findTab(browser);
                            String title = tab != null ? tab.title : "Untitled";
                            String url = tab != null ? tab.url : pageUrl;
                            addBookmark(title, url);
                        });
                        return true;

                    case MENU_INSPECT:
                        // JCEF DevTools — open in a new window
                        SwingUtilities.invokeLater(() -> openDevTools(browser));
                        return true;

                    case MENU_BACK:
                        browser.goBack();
                        return true;

                    case MENU_FORWARD:
                        browser.goForward();
                        return true;

                    case MENU_RELOAD:
                        browser.reload();
                        return true;

                    default:
                        return false;
                }
            }

            @Override
            public void onContextMenuDismissed(CefBrowser browser, CefFrame cefFrame) {
                // No cleanup needed
            }
        });

        cefClient.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                         String targetUrl, String targetFrameName) {
                SwingUtilities.invokeLater(() -> {
                    TabInfo newTab = createTab(false);
                    newTab.browser.loadURL(targetUrl);
                });
                return true;
            }
        });
    }

    private String getCachePath() {
        String home = System.getProperty("user.home");
        return home + java.io.File.separator + "Documents"
                + java.io.File.separator + "echelon"
                + java.io.File.separator + "desktop"
                + java.io.File.separator + "browser_cache";
    }

    private void buildUI() {
        JPanel navBar = new JPanel(new BorderLayout(4, 0));
        navBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));

        backButton = navButton("\u2190", "Back", e -> {
            TabInfo tab = getActiveTab(); if (tab != null) tab.browser.goBack();
        });
        navButtons.add(backButton);

        forwardButton = navButton("\u2192", "Forward", e -> {
            TabInfo tab = getActiveTab(); if (tab != null) tab.browser.goForward();
        });
        navButtons.add(forwardButton);

        refreshButton = navButton("\u27F3", "Refresh", e -> {
            TabInfo tab = getActiveTab();
            if (tab != null) {
                if ("\u2715".equals(refreshButton.getText())) tab.browser.stopLoad();
                else tab.browser.reload();
            }
        });
        navButtons.add(refreshButton);

        navButtons.add(navButton("\u2302", "Home", e -> {
            TabInfo tab = getActiveTab(); if (tab != null) tab.browser.loadURL(HOME_URL);
        }));

        navButtons.add(navButton("+", "New Tab (Ctrl+T)", e -> openNewTab(false)));

        navBar.add(navButtons, BorderLayout.WEST);

        urlField = new JTextField(HOME_URL);
        urlField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        urlField.addActionListener(e -> navigateToUrl());
        urlField.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(urlField::selectAll);
            }
        });
        navBar.add(urlField, BorderLayout.CENTER);

        goButton = new JButton("Go");
        goButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        goButton.addActionListener(e -> navigateToUrl());
        navBar.add(goButton, BorderLayout.EAST);

        // ── Bookmarks bar ──
        bookmarksBar = new BookmarksBar(url -> {
            // Handle __NEW_TAB__ prefix for middle-click / "Open in New Tab"
            if (url.startsWith("__NEW_TAB__:")) {
                String realUrl = url.substring("__NEW_TAB__:".length());
                TabInfo newTab = createTab(false);
                newTab.browser.loadURL(realUrl);
            } else {
                TabInfo tab = getActiveTab();
                if (tab != null) tab.browser.loadURL(url);
            }
        });
        bookmarksBar.setAddBookmarkCallback(() -> {
            TabInfo tab = getActiveTab();
            if (tab != null) {
                bookmarksBar.addBookmark(tab.title, tab.url);
                statusLabel.setText("\u2605 Bookmarked: " + tab.title);
                Timer bkT = new Timer(3000, ev -> statusLabel.setText(" "));
                bkT.setRepeats(false);
                bkT.start();
            }
        });

        // Stack navBar + bookmarksBar vertically
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.add(navBar, BorderLayout.NORTH);
        topPanel.add(bookmarksBar, BorderLayout.SOUTH);
        frame.add(topPanel, BorderLayout.NORTH);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
        tabbedPane.addChangeListener(this::onTabChanged);
        frame.add(tabbedPane, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        frame.add(statusLabel, BorderLayout.SOUTH);

        InputMap im = frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = frame.getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "newTab");
        am.put("newTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openNewTab(false); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "closeTab");
        am.put("closeTab", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { closeCurrentTab(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "focusUrl");
        am.put("focusUrl", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                urlField.requestFocusInWindow(); urlField.selectAll();
            }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_N,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "newIncognito");
        am.put("newIncognito", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openNewTab(true); }
        });

        // Ctrl+D → bookmark current page
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "addBookmark");
        am.put("addBookmark", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                TabInfo tab = getActiveTab();
                if (tab != null && bookmarksBar != null) {
                    bookmarksBar.addBookmark(tab.title, tab.url);
                    statusLabel.setText("\u2605 Bookmarked: " + tab.title);
                    Timer bkT = new Timer(3000, ev -> statusLabel.setText(" "));
                    bkT.setRepeats(false);
                    bkT.start();
                }
            }
        });

        // Ctrl+Shift+B → toggle bookmarks bar visibility
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_B,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "toggleBookmarks");
        am.put("toggleBookmarks", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (bookmarksBar != null) {
                    bookmarksBar.setVisible(!bookmarksBar.isVisible());
                    frame.revalidate();
                }
            }
        });
    }

    private JButton navButton(String text, String tooltip, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setToolTipText(tooltip);
        btn.setFont(new Font("SansSerif", Font.BOLD, 16));
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.addActionListener(action);
        return btn;
    }

    // ====================== Snap Panel Setup ======================

    private echelon.ui.SnapPanel snapPanel;

    // ====================== Autofill Setup ======================

    private void setupAutofill() {
        autofillManager = new echelon.ui.snap.autofill.AutofillManager(frame);

        // JS executor — bridges AutofillManager to CefBrowser
        autofillManager.setJsExecutor((browser, js) -> {
            if (browser instanceof CefBrowser) {
                ((CefBrowser) browser).executeJavaScript(js, "echelon://autofill-fill", 0);
            }
        });

        // Authenticator — face auth first, PIN fallback
        autofillManager.setAuthenticator(callback -> {
            showFaceAuthDialog(callback);
        });

        // Auto-unlock vault with a machine-derived key.
        // The vault decrypts on the same system automatically.
        // Authentication still gates the actual FILLING of credentials.
        try {
            String machineKey = System.getProperty("user.name", "echelon")
                    + ":" + System.getProperty("user.home", "default");
            autofillManager.unlockVault(machineKey);
            System.out.println("[Autofill] Vault unlocked (" +
                    autofillManager.getVault().getAll().size() + " credentials loaded)");
        } catch (Exception e) {
            System.err.println("[Autofill] Failed to unlock vault: " + e.getMessage());
            try {
                String machineKey = System.getProperty("user.name", "echelon")
                        + ":" + System.getProperty("user.home", "default");
                java.nio.file.Path vaultPath = autofillManager.getVault().getVaultPath();
                if (vaultPath != null && java.nio.file.Files.exists(vaultPath)) {
                    java.nio.file.Files.delete(vaultPath);
                }
                autofillManager.unlockVault(machineKey);
            } catch (Exception ex) {
                System.err.println("[Autofill] Could not initialize vault: " + ex.getMessage());
            }
        }
    }

    /**
     * Shows face authentication dialog with live webcam preview.
     * On failure or no face registered, falls back to PIN.
     */
    private void showFaceAuthDialog(java.util.function.Consumer<Boolean> callback) {
        String currentUser = System.getProperty("user.name", "echelon");
        String savedFacePath;
        try {
            java.nio.file.Path usersDir = echelon.util.DirectoryConfig.usersDir(true);
            savedFacePath = usersDir.resolve(currentUser)
                    .resolve(currentUser + "_face.png").toString();
        } catch (Exception e) {
            savedFacePath = System.getProperty("user.home") + java.io.File.separator
                    + "Documents" + java.io.File.separator + "echelon"
                    + java.io.File.separator + "users" + java.io.File.separator
                    + currentUser + java.io.File.separator + currentUser + "_face.png";
        }

        // If no face registered, skip straight to PIN
        if (!new java.io.File(savedFacePath).exists()) {
            System.out.println("[Autofill] No face data found, falling back to PIN");
            showAutofillPinDialog(callback);
            return;
        }

        final String facePath = savedFacePath;

        SwingUtilities.invokeLater(() -> {
            JDialog dialog = new JDialog(frame, "Echelon Authentication", true);
            dialog.setSize(340, 320);
            dialog.setLocationRelativeTo(frame);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setResizable(false);

            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JLabel statusLabel = new JLabel("\uD83D\uDD12  Verifying face...", SwingConstants.CENTER);
            statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
            root.add(statusLabel, BorderLayout.NORTH);

            JLabel previewLabel = new JLabel();
            previewLabel.setHorizontalAlignment(SwingConstants.CENTER);
            previewLabel.setPreferredSize(new Dimension(300, 220));
            previewLabel.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));
            root.add(previewLabel, BorderLayout.CENTER);

            JButton pinFallbackBtn = new JButton("Use PIN Instead");
            pinFallbackBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
            pinFallbackBtn.addActionListener(e -> {
                dialog.dispose();
                showAutofillPinDialog(callback);
            });
            JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            bottomPanel.add(pinFallbackBtn);
            root.add(bottomPanel, BorderLayout.SOUTH);

            dialog.setContentPane(root);

            final boolean[] resultDelivered = {false};

            dialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent e) {
                    if (!resultDelivered[0]) {
                        callback.accept(false);
                    }
                }
            });

            // Face scan in background
            new Thread(() -> {
                try {
                    echelon.auth.FaceAuthenticator faceAuth = new echelon.auth.FaceAuthenticator();
                    org.bytedeco.javacv.OpenCVFrameGrabber grabber =
                            new org.bytedeco.javacv.OpenCVFrameGrabber(0);
                    org.bytedeco.javacv.Java2DFrameConverter converter2D =
                            new org.bytedeco.javacv.Java2DFrameConverter();
                    org.bytedeco.javacv.OpenCVFrameConverter.ToMat converter =
                            new org.bytedeco.javacv.OpenCVFrameConverter.ToMat();

                    grabber.start();
                    org.bytedeco.opencv.opencv_core.Mat latestFace = null;
                    long startTime = System.currentTimeMillis();

                    while (System.currentTimeMillis() - startTime < 5000) {
                        if (!dialog.isVisible()) break;
                        org.bytedeco.javacv.Frame grabbed = grabber.grab();
                        if (grabbed == null) continue;

                        org.bytedeco.opencv.opencv_core.Mat colorImage = converter.convert(grabbed);
                        if (colorImage == null || colorImage.empty()) continue;

                        // Show live preview
                        Image img = converter2D.getBufferedImage(converter.convert(colorImage), 1);
                        if (img != null) {
                            Image scaled = img.getScaledInstance(300, 220, Image.SCALE_FAST);
                            SwingUtilities.invokeLater(() ->
                                    previewLabel.setIcon(new ImageIcon(scaled)));
                        }

                        // Try face detection
                        org.bytedeco.opencv.opencv_core.Mat detected =
                                faceAuth.detectAndCropFace(colorImage);
                        if (detected != null) {
                            latestFace = detected;
                            SwingUtilities.invokeLater(() ->
                                    statusLabel.setText("\uD83D\uDD12  Face detected, verifying..."));
                        }
                        Thread.sleep(100);
                    }
                    grabber.stop();

                    if (latestFace != null) {
                        boolean success = faceAuth.verifyFace(latestFace, facePath);
                        SwingUtilities.invokeLater(() -> {
                            if (success) {
                                statusLabel.setText("\u2705  Face recognized!");
                                resultDelivered[0] = true;
                                callback.accept(true);
                                Timer t = new Timer(600, e2 -> dialog.dispose());
                                t.setRepeats(false);
                                t.start();
                            } else {
                                statusLabel.setText("\u274C  Face not recognized");
                                Timer t = new Timer(1000, e2 -> {
                                    dialog.dispose();
                                    if (!resultDelivered[0]) showAutofillPinDialog(callback);
                                });
                                t.setRepeats(false);
                                t.start();
                            }
                        });
                    } else {
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("No face detected");
                            Timer t = new Timer(1000, e2 -> {
                                dialog.dispose();
                                if (!resultDelivered[0]) showAutofillPinDialog(callback);
                            });
                            t.setRepeats(false);
                            t.start();
                        });
                    }
                } catch (Exception ex) {
                    System.err.println("[Autofill] Face auth error: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        dialog.dispose();
                        if (!resultDelivered[0]) showAutofillPinDialog(callback);
                    });
                }
            }, "autofill-face-auth").start();

            dialog.setVisible(true);
        });
    }

    private void showAutofillPinDialog(java.util.function.Consumer<Boolean> callback) {
        SwingUtilities.invokeLater(() -> {
            JPasswordField pinField = new JPasswordField(6);
            pinField.setFont(new Font("SansSerif", Font.BOLD, 18));
            pinField.setHorizontalAlignment(JTextField.CENTER);

            JPanel panel = new JPanel(new BorderLayout(0, 8));
            JLabel label = new JLabel("\uD83D\uDD12  Verify identity to autofill");
            label.setFont(new Font("SansSerif", Font.PLAIN, 12));
            panel.add(label, BorderLayout.NORTH);
            panel.add(pinField, BorderLayout.CENTER);

            SwingUtilities.invokeLater(pinField::requestFocusInWindow);

            int result = JOptionPane.showConfirmDialog(frame, panel,
                    "Echelon Authentication",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (result == JOptionPane.OK_OPTION) {
                char[] pin = pinField.getPassword();
                boolean valid = pin.length > 0;
                java.util.Arrays.fill(pin, '\0');
                callback.accept(valid);
            } else {
                callback.accept(false);
            }
        });
    }

    private void setupSnapPanel() {
        snapPanel = echelon.ui.SnapPanel.getOrCreate(frame, "Chrome Controls");
        snapPanel.setSnapWidth(300);

        JPanel content = snapPanel.getContentPanel();
        content.setLayout(new BorderLayout());

        // --- Tab list panel (top) ---
        JPanel tabListPanel = new JPanel(new BorderLayout()) {
            @Override
            public Dimension getMaximumSize() {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        tabListPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JLabel tabListLabel = new JLabel("Open Tabs");
        tabListLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        tabListPanel.add(tabListLabel, BorderLayout.NORTH);

        DefaultListModel<String> tabListModel = new DefaultListModel<>();
        JList<String> tabList = new JList<>(tabListModel);
        tabList.setFont(new Font("SansSerif", Font.PLAIN, 11));
        tabList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = tabList.getSelectedIndex();
                if (idx >= 0 && idx < tabs.size()) {
                    tabbedPane.setSelectedIndex(idx);
                }
            }
        });
        JScrollPane tabScroll = new JScrollPane(tabList);
        tabScroll.setPreferredSize(new Dimension(0, 120));
        tabScroll.setMinimumSize(new Dimension(0, 60));
        tabListPanel.add(tabScroll, BorderLayout.CENTER);

        // --- Quick actions (middle) ---
        JPanel actionsPanel = new JPanel(new GridLayout(0, 2, 4, 4)) {
            @Override
            public Dimension getMaximumSize() {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        actionsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JButton newTabBtn = new JButton("New Tab");
        newTabBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        newTabBtn.addActionListener(e -> openNewTab(false));
        actionsPanel.add(newTabBtn);

        JButton incognitoBtn = new JButton("Incognito");
        incognitoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        incognitoBtn.addActionListener(e -> openNewTab(true));
        actionsPanel.add(incognitoBtn);

        JButton closeTabBtn = new JButton("Close Tab");
        closeTabBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        closeTabBtn.addActionListener(e -> closeCurrentTab());
        actionsPanel.add(closeTabBtn);

        JButton closeAllBtn = new JButton("Close All");
        closeAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        closeAllBtn.addActionListener(e -> closeAllTabs());
        actionsPanel.add(closeAllBtn);

        JButton homeBtn = new JButton("Go Home");
        homeBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        homeBtn.addActionListener(e -> {
            TabInfo tab = getActiveTab();
            if (tab != null) tab.browser.loadURL(HOME_URL);
        });
        actionsPanel.add(homeBtn);

        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        refreshBtn.addActionListener(e -> {
            TabInfo tab = getActiveTab();
            if (tab != null) tab.browser.reload();
        });
        actionsPanel.add(refreshBtn);

        // --- Everything in one scrollable vertical panel ---
        JPanel allContent = new JPanel();
        allContent.setLayout(new BoxLayout(allContent, BoxLayout.Y_AXIS));

        tabListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        actionsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        allContent.add(tabListPanel);
        allContent.add(actionsPanel);

        // === Scripts section (collapsible, expanded by default) ===
        JPanel scriptsWrapper = createCollapsibleWrapper();

        JPanel scriptsHeader = new JPanel(new BorderLayout());
        scriptsHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        scriptsHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel scriptsToggle = new JLabel("\u25BC  Scripts");
        scriptsToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        scriptsHeader.add(scriptsToggle, BorderLayout.WEST);

        // Refresh button on the right of the header
        JButton scriptsRefreshBtn = new JButton("\u27F3");
        scriptsRefreshBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
        scriptsRefreshBtn.setToolTipText("Refresh script list");
        scriptsRefreshBtn.setFocusable(false);
        scriptsRefreshBtn.setMargin(new Insets(0, 4, 0, 4));
        scriptsRefreshBtn.setBorderPainted(false);
        scriptsRefreshBtn.setContentAreaFilled(false);
        scriptsHeader.add(scriptsRefreshBtn, BorderLayout.EAST);

        JPanel scriptsContent = new JPanel();
        scriptsContent.setLayout(new BoxLayout(scriptsContent, BoxLayout.Y_AXIS));
        scriptsContent.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        scriptsContent.setVisible(true); // expanded by default

        // Populate script list from DirectoryConfig
        Runnable refreshScripts = () -> {
            scriptsContent.removeAll();
            java.nio.file.Path scriptsDir = getScriptsDir();
            if (scriptsDir == null || !java.nio.file.Files.isDirectory(scriptsDir)) {
                JLabel noScripts = new JLabel("No scripts directory found");
                noScripts.setFont(new Font("SansSerif", Font.ITALIC, 11));
                noScripts.setForeground(UIManager.getColor("Label.disabledForeground"));
                scriptsContent.add(noScripts);
            } else {
                try {
                    java.io.File[] scriptFiles = scriptsDir.toFile().listFiles(
                            (dir, name) -> name.endsWith(".java")
                    );
                    if (scriptFiles == null || scriptFiles.length == 0) {
                        JLabel noScripts = new JLabel("No .java scripts found");
                        noScripts.setFont(new Font("SansSerif", Font.ITALIC, 11));
                        noScripts.setForeground(UIManager.getColor("Label.disabledForeground"));
                        scriptsContent.add(noScripts);
                    } else {
                        java.util.Arrays.sort(scriptFiles, java.util.Comparator.comparing(java.io.File::getName));
                        for (java.io.File sf : scriptFiles) {
                            JPanel row = new JPanel(new BorderLayout(4, 0));
                            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
                            row.setAlignmentX(Component.LEFT_ALIGNMENT);
                            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

                            String displayName = sf.getName().replace(".java", "");
                            JLabel nameLabel = new JLabel(displayName);
                            nameLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
                            nameLabel.setToolTipText(sf.getAbsolutePath());
                            row.add(nameLabel, BorderLayout.CENTER);

                            JButton runBtn = new JButton("\u25B6");
                            runBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
                            runBtn.setToolTipText("Run " + sf.getName());
                            runBtn.setFocusable(false);
                            runBtn.setMargin(new Insets(1, 6, 1, 6));
                            runBtn.addActionListener(ev -> runScript(sf));
                            row.add(runBtn, BorderLayout.EAST);

                            scriptsContent.add(row);
                        }
                    }
                } catch (Exception ex) {
                    JLabel errLabel = new JLabel("Error reading scripts: " + ex.getMessage());
                    errLabel.setFont(new Font("SansSerif", Font.ITALIC, 11));
                    errLabel.setForeground(new Color(0xFF5F57));
                    scriptsContent.add(errLabel);
                }
            }
            scriptsContent.revalidate();
            scriptsContent.repaint();
        };

        refreshScripts.run(); // initial populate
        scriptsRefreshBtn.addActionListener(e -> refreshScripts.run());

        // Toggle scripts collapse
        scriptsHeader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getSource() == scriptsRefreshBtn) return;
                boolean show = !scriptsContent.isVisible();
                scriptsContent.setVisible(show);
                scriptsToggle.setText((show ? "\u25BC  " : "\u25B6  ") + "Scripts");
                revalidateScrollable(allContent);
            }
        });

        scriptsWrapper.add(scriptsHeader);
        scriptsWrapper.add(scriptsContent);
        allContent.add(scriptsWrapper);

        // === Extensions section (collapsible, collapsed by default) ===
        JPanel extensionWrapper = createCollapsibleWrapper();

        JPanel extHeader = new JPanel(new BorderLayout());
        extHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        JLabel extToggleLabel = new JLabel("\u25B6  Extensions");
        extToggleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        extHeader.add(extToggleLabel, BorderLayout.WEST);

        JPanel extContent = new JPanel();
        extContent.setLayout(new BoxLayout(extContent, BoxLayout.Y_AXIS));
        extContent.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        extContent.setVisible(false); // collapsed by default

        JLabel placeholder = new JLabel("No extensions loaded");
        placeholder.setFont(new Font("SansSerif", Font.ITALIC, 11));
        placeholder.setForeground(UIManager.getColor("Label.disabledForeground"));
        placeholder.setAlignmentX(Component.LEFT_ALIGNMENT);
        extContent.add(placeholder);

        // Toggle collapse/expand on click
        extHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        extHeader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean show = !extContent.isVisible();
                extContent.setVisible(show);
                extToggleLabel.setText((show ? "\u25BC  " : "\u25B6  ") + "Extensions");
                revalidateScrollable(allContent);
            }
        });

        extensionWrapper.add(extHeader);
        extensionWrapper.add(extContent);
        allContent.add(extensionWrapper);

        // === Bookmarks section (collapsible, collapsed by default) ===
        JPanel bookmarksWrapper = createCollapsibleWrapper();

        JPanel bmHeader = new JPanel(new BorderLayout());
        bmHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        JLabel bmToggle = new JLabel("\u25B6  Bookmarks");
        bmToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        bmHeader.add(bmToggle, BorderLayout.WEST);
        bmHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel bmContent = new JPanel();
        bmContent.setLayout(new BoxLayout(bmContent, BoxLayout.Y_AXIS));
        bmContent.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        bmContent.setVisible(false); // collapsed by default

        // JTree for bookmark folders/items
        javax.swing.tree.DefaultMutableTreeNode bmRoot =
                new javax.swing.tree.DefaultMutableTreeNode("Bookmarks");
        javax.swing.tree.DefaultTreeModel bmTreeModel =
                new javax.swing.tree.DefaultTreeModel(bmRoot);
        JTree bmTree = new JTree(bmTreeModel);
        bmTree.setRootVisible(false);
        bmTree.setShowsRootHandles(true);
        bmTree.setFont(new Font("SansSerif", Font.PLAIN, 11));
        bmTree.setRowHeight(22);
        bmTree.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        // Custom renderer for bookmark icons
        bmTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                setFont(new Font("SansSerif", Font.PLAIN, 11));
                javax.swing.tree.DefaultMutableTreeNode node =
                        (javax.swing.tree.DefaultMutableTreeNode) value;
                Object userObj = node.getUserObject();
                if (userObj instanceof BookmarkNodeData) {
                    BookmarkNodeData data = (BookmarkNodeData) userObj;
                    if (data.isFolder) {
                        setText("\uD83D\uDCC1 " + data.title);
                    } else {
                        setText("\uD83C\uDF10 " + data.title);
                        setToolTipText(data.url);
                    }
                }
                return this;
            }
        });

        // Double-click to navigate
        bmTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    javax.swing.tree.TreePath path = bmTree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    javax.swing.tree.DefaultMutableTreeNode node =
                            (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
                    Object userObj = node.getUserObject();
                    if (userObj instanceof BookmarkNodeData) {
                        BookmarkNodeData data = (BookmarkNodeData) userObj;
                        if (!data.isFolder && data.url != null) {
                            TabInfo tab = getActiveTab();
                            if (tab != null) tab.browser.loadURL(data.url);
                        }
                    }
                }
            }
        });

        // Refresh logic for bookmark tree
        Runnable refreshBmTree = () -> {
            bmRoot.removeAllChildren();
            if (bookmarksBar == null) {
                bmTreeModel.reload();
                return;
            }
            // Build folder map
            java.util.Map<String, javax.swing.tree.DefaultMutableTreeNode> folderNodes =
                    new java.util.LinkedHashMap<>();
            java.util.List<BookmarksBar.BookmarkEntry> all = bookmarksBar.getAllEntries();
            for (BookmarksBar.BookmarkEntry bm : all) {
                if (bm.isInFolder()) {
                    javax.swing.tree.DefaultMutableTreeNode folderNode = folderNodes.get(bm.folder);
                    if (folderNode == null) {
                        folderNode = new javax.swing.tree.DefaultMutableTreeNode(
                                new BookmarkNodeData(bm.folder, null, true));
                        folderNodes.put(bm.folder, folderNode);
                        bmRoot.add(folderNode);
                    }
                    folderNode.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new BookmarkNodeData(bm.title, bm.url, false)));
                } else {
                    bmRoot.add(new javax.swing.tree.DefaultMutableTreeNode(
                            new BookmarkNodeData(bm.title, bm.url, false)));
                }
            }
            bmTreeModel.reload();
            // Expand all folders
            for (int i = 0; i < bmTree.getRowCount(); i++) {
                bmTree.expandRow(i);
            }
        };

        JScrollPane bmScroll = new JScrollPane(bmTree);
        bmScroll.setPreferredSize(new Dimension(0, 140));
        bmScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 140));
        bmScroll.setMinimumSize(new Dimension(0, 60));
        bmScroll.setBorder(BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor")));
        bmScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        bmContent.add(bmScroll);

        // Toggle bookmarks section
        bmHeader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean show = !bmContent.isVisible();
                bmContent.setVisible(show);
                bmToggle.setText((show ? "\u25BC  " : "\u25B6  ") + "Bookmarks");
                if (show) refreshBmTree.run();
                revalidateScrollable(allContent);
            }
        });

        bookmarksWrapper.add(bmHeader);
        bookmarksWrapper.add(bmContent);
        allContent.add(bookmarksWrapper);

        // === Quick Settings section (collapsible, collapsed by default) ===
        JPanel settingsWrapper = createCollapsibleWrapper();

        JPanel setHeader = new JPanel(new BorderLayout());
        setHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        JLabel setToggle = new JLabel("\u25B6  Quick Settings");
        setToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        setHeader.add(setToggle, BorderLayout.WEST);
        setHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel setContent = new JPanel();
        setContent.setLayout(new BoxLayout(setContent, BoxLayout.Y_AXIS));
        setContent.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        setContent.setVisible(false); // collapsed by default

        // Toggle: Show Bookmarks Bar
        JCheckBox showBmBarCb = new JCheckBox("Show Bookmarks Bar", true);
        showBmBarCb.setFont(new Font("SansSerif", Font.PLAIN, 11));
        showBmBarCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        showBmBarCb.addActionListener(e -> {
            if (bookmarksBar != null) {
                bookmarksBar.setVisible(showBmBarCb.isSelected());
                frame.revalidate();
            }
        });
        setContent.add(showBmBarCb);
        setContent.add(Box.createVerticalStrut(4));

        // Info label for keyboard shortcut
        JLabel bmBarHint = new JLabel("   Ctrl+Shift+B to toggle");
        bmBarHint.setFont(new Font("SansSerif", Font.ITALIC, 10));
        bmBarHint.setForeground(UIManager.getColor("Label.disabledForeground"));
        bmBarHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        setContent.add(bmBarHint);

        // Toggle settings section
        setHeader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean show = !setContent.isVisible();
                setContent.setVisible(show);
                setToggle.setText((show ? "\u25BC  " : "\u25B6  ") + "Quick Settings");
                // Sync checkbox state when opening
                if (show && bookmarksBar != null) {
                    showBmBarCb.setSelected(bookmarksBar.isVisible());
                }
                revalidateScrollable(allContent);
            }
        });

        settingsWrapper.add(setHeader);
        settingsWrapper.add(setContent);
        allContent.add(settingsWrapper);

        // === Utilities section (collapsible, collapsed by default) ===
        JPanel utilitiesWrapper = createCollapsibleWrapper();

        JPanel utilHeader = new JPanel(new BorderLayout());
        utilHeader.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)
        ));
        utilHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel utilToggle = new JLabel("\u25B6  Utilities");
        utilToggle.setFont(new Font("SansSerif", Font.BOLD, 12));
        utilHeader.add(utilToggle, BorderLayout.WEST);

        JPanel utilContent = new JPanel();
        utilContent.setLayout(new BoxLayout(utilContent, BoxLayout.Y_AXIS));
        utilContent.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        utilContent.setVisible(false); // collapsed by default

        // ── Capture Text row: label + [Area] + [Full] ──
        JPanel captureRow = new JPanel(new BorderLayout(4, 0));
        captureRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        captureRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel capLabel = new JLabel("Capture Text");
        capLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        captureRow.add(capLabel, BorderLayout.CENTER);

        JPanel capButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        capButtons.setOpaque(false);

        JButton areaBtn = new JButton("Area");
        areaBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        areaBtn.setMargin(new Insets(1, 6, 1, 6));
        areaBtn.setFocusable(false);
        areaBtn.setToolTipText("Select a screen region to capture text (OCR)");

        JButton fullBtn = new JButton("Full");
        fullBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        fullBtn.setMargin(new Insets(1, 6, 1, 6));
        fullBtn.setFocusable(false);
        fullBtn.setToolTipText("Capture all visible text from the current page");

        capButtons.add(areaBtn);
        capButtons.add(fullBtn);
        captureRow.add(capButtons, BorderLayout.EAST);
        utilContent.add(captureRow);
        utilContent.add(Box.createVerticalStrut(4));

        // ── Capture history list ──
        // Each entry: { timestamp, text }
        java.util.List<String[]> captureHistory = new java.util.ArrayList<>();

        JPanel captureListPanel = new JPanel();
        captureListPanel.setLayout(new BoxLayout(captureListPanel, BoxLayout.Y_AXIS));
        captureListPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Preview panel shown inline when a capture entry is clicked
        JPanel previewContainer = new JPanel(new BorderLayout());
        previewContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        previewContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        previewContainer.setVisible(false);

        JTextArea previewArea = new JTextArea();
        previewArea.setEditable(false);
        previewArea.setLineWrap(true);
        previewArea.setWrapStyleWord(true);
        previewArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        JScrollPane previewScroll = new JScrollPane(previewArea);
        previewScroll.setPreferredSize(new Dimension(0, 140));
        previewScroll.setBorder(BorderFactory.createLineBorder(
                UIManager.getColor("Component.borderColor")));

        // Copy button inside preview
        JPanel previewToolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        previewToolbar.setOpaque(false);
        JButton previewCopyBtn = new JButton("Copy");
        previewCopyBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        previewCopyBtn.setMargin(new Insets(0, 6, 0, 6));
        previewCopyBtn.setFocusable(false);
        previewCopyBtn.addActionListener(ev -> {
            String txt = previewArea.getText();
            if (txt != null && !txt.isEmpty()) {
                java.awt.datatransfer.StringSelection sel =
                        new java.awt.datatransfer.StringSelection(txt);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                previewCopyBtn.setText("Copied \u2713");
                Timer copyTimer = new Timer(1500, te -> previewCopyBtn.setText("Copy"));
                copyTimer.setRepeats(false);
                copyTimer.start();
            }
        });
        previewToolbar.add(previewCopyBtn);

        previewContainer.add(previewScroll, BorderLayout.CENTER);
        previewContainer.add(previewToolbar, BorderLayout.SOUTH);

        // Track which entry is currently expanded (-1 = none)
        final int[] expandedIndex = { -1 };
        // Holder so the Runnable can reference itself inside lambdas
        final Runnable[] refreshHolder = new Runnable[1];

        // Rebuild the capture list UI
        Runnable refreshCaptureList = () -> {
            captureListPanel.removeAll();
            if (captureHistory.isEmpty()) {
                JLabel noCaptures = new JLabel("No captures yet");
                noCaptures.setFont(new Font("SansSerif", Font.ITALIC, 11));
                noCaptures.setForeground(UIManager.getColor("Label.disabledForeground"));
                noCaptures.setAlignmentX(Component.LEFT_ALIGNMENT);
                captureListPanel.add(noCaptures);
                previewContainer.setVisible(false);
                expandedIndex[0] = -1;
            } else {
                for (int i = 0; i < captureHistory.size(); i++) {
                    final int idx = i;
                    String[] entry = captureHistory.get(i);
                    String timestamp = entry[0];
                    String capturedText = entry[1];

                    JPanel entryRow = new JPanel(new BorderLayout(4, 0));
                    entryRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
                    entryRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                    entryRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    entryRow.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));

                    // Arrow indicator + label
                    boolean isExpanded = (expandedIndex[0] == idx);
                    String arrow = isExpanded ? "\u25BC " : "\u25B6 ";
                    // Truncate preview of text for the label
                    String preview = capturedText.replace('\n', ' ').trim();
                    if (preview.length() > 30) preview = preview.substring(0, 30) + "\u2026";

                    JLabel entryLabel = new JLabel(arrow + "Capture " + timestamp);
                    entryLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
                    entryLabel.setToolTipText(preview);
                    entryRow.add(entryLabel, BorderLayout.CENTER);

                    // Delete button (x)
                    JButton delBtn = new JButton("\u2715");
                    delBtn.setFont(new Font("SansSerif", Font.PLAIN, 9));
                    delBtn.setMargin(new Insets(0, 3, 0, 3));
                    delBtn.setFocusable(false);
                    delBtn.setBorderPainted(false);
                    delBtn.setContentAreaFilled(false);
                    delBtn.setToolTipText("Remove this capture");
                    delBtn.addActionListener(ev -> {
                        captureHistory.remove(idx);
                        if (expandedIndex[0] == idx) {
                            expandedIndex[0] = -1;
                            previewContainer.setVisible(false);
                        } else if (expandedIndex[0] > idx) {
                            expandedIndex[0]--;
                        }
                        refreshHolder[0].run();
                    });
                    entryRow.add(delBtn, BorderLayout.EAST);

                    // Click to expand/collapse preview
                    entryRow.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseClicked(MouseEvent e) {
                            if (e.getSource() == delBtn) return;
                            if (expandedIndex[0] == idx) {
                                // Collapse
                                expandedIndex[0] = -1;
                                previewContainer.setVisible(false);
                            } else {
                                // Expand this entry
                                expandedIndex[0] = idx;
                                previewArea.setText(capturedText);
                                previewArea.setCaretPosition(0);
                                previewContainer.setVisible(true);
                            }
                            // Refresh arrows
                            for (int c = 0; c < captureListPanel.getComponentCount(); c++) {
                                Component comp = captureListPanel.getComponent(c);
                                if (comp instanceof JPanel) {
                                    JPanel row = (JPanel) comp;
                                    Component first = row.getComponent(0);
                                    if (first instanceof JLabel) {
                                        JLabel lbl = (JLabel) first;
                                        String text = lbl.getText();
                                        // Replace arrow prefix
                                        String body = text.substring(2); // skip old arrow + space
                                        String newArrow = (expandedIndex[0] == c) ? "\u25BC " : "\u25B6 ";
                                        lbl.setText(newArrow + body);
                                    }
                                }
                            }
                            revalidateScrollable(allContent);
                        }
                    });

                    captureListPanel.add(entryRow);

                    // Insert preview container right after the expanded entry
                    if (isExpanded) {
                        captureListPanel.add(previewContainer);
                    }
                }

                // If nothing expanded, make sure preview is detached but still around
                if (expandedIndex[0] < 0) {
                    previewContainer.setVisible(false);
                }
            }

            captureListPanel.revalidate();
            captureListPanel.repaint();
            revalidateScrollable(allContent);
        };

        // Helper: add a capture entry and refresh the list
        refreshHolder[0] = refreshCaptureList; // allow self-reference from delete buttons

        java.util.function.BiConsumer<String, String> addCapture = (timestamp, text) -> {
            captureHistory.add(0, new String[]{ timestamp, text }); // newest first
            // Auto-expand the newest
            expandedIndex[0] = 0;
            previewArea.setText(text);
            previewArea.setCaretPosition(0);
            previewContainer.setVisible(true);
            refreshCaptureList.run();
        };

        // ── [Area] button action — OCR screen selection ──
        areaBtn.addActionListener(e -> {
            // Minimize the browser to let user select behind it (optional)
            // frame.setState(JFrame.ICONIFIED);
            triton.util.TextCapture.capture();
            // TextCapture copies text to clipboard on completion.
            // Poll clipboard briefly after a delay to grab the result.
            Timer pollTimer = new Timer(500, null);
            final long startTime = System.currentTimeMillis();
            final String[] clipBefore = { getClipboardText() };
            pollTimer.addActionListener(tick -> {
                String current = getClipboardText();
                boolean changed = current != null && !current.equals(clipBefore[0])
                        && !current.isBlank();
                boolean timeout = System.currentTimeMillis() - startTime > 60_000; // 60s max
                if (changed || timeout) {
                    pollTimer.stop();
                    if (changed) {
                        String ts = new java.text.SimpleDateFormat("MM/dd HH:mm:ss")
                                .format(new java.util.Date());
                        SwingUtilities.invokeLater(() -> addCapture.accept(ts, current));
                    }
                }
            });
            pollTimer.setRepeats(true);
            pollTimer.start();
        });

        // ── [Full] button action — capture page text via JS + clipboard ──
        fullBtn.addActionListener(e -> {
            TabInfo tab = getActiveTab();
            if (tab == null) return;

            // Save current clipboard so we can detect the change
            final String clipBefore = getClipboardText();

            // Use JS to copy innerText to clipboard via execCommand
            tab.browser.executeJavaScript(
                    "(function() {" +
                    "  var ta = document.createElement('textarea');" +
                    "  ta.value = document.body.innerText;" +
                    "  ta.style.position = 'fixed';" +
                    "  ta.style.opacity = '0';" +
                    "  document.body.appendChild(ta);" +
                    "  ta.select();" +
                    "  document.execCommand('copy');" +
                    "  document.body.removeChild(ta);" +
                    "})();",
                    "echelon://textcapture", 0);

            // Poll clipboard for the result
            Timer readTimer = new Timer(400, null);
            final long t0 = System.currentTimeMillis();
            readTimer.addActionListener(tick -> {
                String current = getClipboardText();
                boolean changed = current != null && !current.equals(clipBefore)
                        && !current.isBlank();
                boolean timeout = System.currentTimeMillis() - t0 > 5000;

                if (changed || timeout) {
                    readTimer.stop();
                    if (changed) {
                        String ts = new java.text.SimpleDateFormat("MM/dd HH:mm:ss")
                                .format(new java.util.Date());
                        addCapture.accept(ts, current);
                        statusLabel.setText("\u2705 Page text captured (" + current.length() + " chars)");
                        Timer clearStatus = new Timer(3000, se -> statusLabel.setText(" "));
                        clearStatus.setRepeats(false);
                        clearStatus.start();
                    } else {
                        statusLabel.setText("Could not capture page text");
                    }
                }
            });
            readTimer.setRepeats(true);
            readTimer.start();
        });

        // Initial populate
        refreshCaptureList.run();

        utilContent.add(captureListPanel);

        // Toggle utilities section
        utilHeader.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                boolean show = !utilContent.isVisible();
                utilContent.setVisible(show);
                utilToggle.setText((show ? "\u25BC  " : "\u25B6  ") + "Utilities");
                revalidateScrollable(allContent);
            }
        });

        utilitiesWrapper.add(utilHeader);
        utilitiesWrapper.add(utilContent);
        allContent.add(utilitiesWrapper);

        // Vertical glue pushes everything to the top
        allContent.add(Box.createVerticalGlue());

        // Force all descendants to left-align so BoxLayout doesn't drift them right
        fixAlignment(allContent);

        // Single scroll pane wrapping all content
        JScrollPane mainScroll = new JScrollPane(allContent);
        mainScroll.setBorder(BorderFactory.createEmptyBorder());
        mainScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        mainScroll.getVerticalScrollBar().setUnitIncrement(12);

        content.add(mainScroll, BorderLayout.CENTER);

        // --- Keep tab list in sync ---
        tabbedPane.addChangeListener(e2 -> refreshSnapTabList(tabListModel, tabList));

        // Periodic refresh to catch title changes
        javax.swing.Timer tabRefreshTimer = new javax.swing.Timer(1000, e2 -> {
            if (snapPanel != null && snapPanel.isVisible()) {
                refreshSnapTabList(tabListModel, tabList);
            }
        });
        tabRefreshTimer.setRepeats(true);
        tabRefreshTimer.start();

        // Clean up timer when frame closes
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { tabRefreshTimer.stop(); }
            @Override public void windowClosing(WindowEvent e) { tabRefreshTimer.stop(); }
        });
    }

    private static String getClipboardText() {
        try {
            java.awt.datatransfer.Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                return (String) clip.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ====================== Collapsible Panel Helpers ======================

    /**
     * Creates a BoxLayout Y_AXIS wrapper that shrinks to fit its visible children.
     * When all children except the header are hidden, it collapses to header height only.
     */
    private static JPanel createCollapsibleWrapper() {
        JPanel wrapper = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                // BoxLayout respects max size — return preferred so it never stretches
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }
        };
        wrapper.setLayout(new BoxLayout(wrapper, BoxLayout.Y_AXIS));
        wrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
        return wrapper;
    }

    /**
     * Recursively set LEFT_ALIGNMENT on all JComponents in a container.
     * Prevents BoxLayout from drifting children with mismatched alignmentX.
     */
    private static void fixAlignment(java.awt.Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComponent) {
                ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            if (child instanceof java.awt.Container) {
                fixAlignment((java.awt.Container) child);
            }
        }
    }

    /**
     * Revalidate the scrollable content panel and its scroll pane ancestor
     * so that collapse/expand changes are reflected immediately.
     */
    private static void revalidateScrollable(JPanel contentPanel) {
        contentPanel.revalidate();
        contentPanel.repaint();
        // Also poke the scroll pane to recalculate
        java.awt.Container parent = contentPanel.getParent();
        while (parent != null) {
            if (parent instanceof JScrollPane) {
                JScrollPane sp = (JScrollPane) parent;
                sp.revalidate();
                sp.repaint();
                break;
            }
            parent = parent.getParent();
        }
    }

    // ====================== Script Runner ======================

    /**
     * Resolves the scripts directory: .../container/applications/ChromeSD/scripts/
     * Creates it if it doesn't exist.
     */
    private java.nio.file.Path getScriptsDir() {
        try {
            java.nio.file.Path appsDir = echelon.util.DirectoryConfig.applicationsDir(true);
            java.nio.file.Path scriptsDir = appsDir.resolve("ChromeSD").resolve("scripts");
            java.nio.file.Files.createDirectories(scriptsDir);
            return scriptsDir;
        } catch (Exception e) {
            System.err.println("[ChromeSD] Failed to resolve scripts directory: " + e.getMessage());
            return null;
        }
    }

    /**
     * Compiles and runs a ChromeSD script (.java file) against the active JCEF tab.
     * The script must implement {@link echelon.ui.snap.ChromeScript}.
     * A {@link echelon.ui.snap.ScriptContext} wrapping the current tab is passed in.
     */
    private void runScript(java.io.File scriptFile) {
        if (scriptFile == null || !scriptFile.exists()) {
            JOptionPane.showMessageDialog(snapPanel,
                    "Script file not found: " + scriptFile,
                    "Run Script", JOptionPane.ERROR_MESSAGE);
            return;
        }

        TabInfo activeTab = getActiveTab();
        if (activeTab == null) {
            JOptionPane.showMessageDialog(snapPanel,
                    "No active tab to run script against.\nOpen a tab first.",
                    "Run Script", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String fileName = scriptFile.getName();

        // Confirm before running
        int confirm = JOptionPane.showConfirmDialog(snapPanel,
                "Run script: " + fileName + "?\n\n"
                        + "This will execute against the current tab:\n"
                        + truncate(activeTab.title, 40) + "\n\n"
                        + "Make sure you trust this script before running.",
                "Run Script", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.OK_OPTION) return;

        // Run in background thread
        new Thread(() -> {
            try {
                System.out.println("[ChromeSD] Compiling script: " + scriptFile.getAbsolutePath());

                // Compile the .java file using the system Java compiler
                javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
                if (compiler == null) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(snapPanel,
                            "Java compiler not available.\n"
                                    + "Make sure you're running with a JDK, not a JRE.",
                            "Compile Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }

                // Compile with current classpath
                String classpath = System.getProperty("java.class.path");
                java.io.File outputDir = scriptFile.getParentFile();

                // Capture compiler output
                java.io.ByteArrayOutputStream errStream = new java.io.ByteArrayOutputStream();
                int result = compiler.run(null, null, new java.io.PrintStream(errStream),
                        "-classpath", classpath,
                        "-d", outputDir.getAbsolutePath(),
                        scriptFile.getAbsolutePath());

                if (result != 0) {
                    String errors = errStream.toString();
                    System.err.println("[ChromeSD] Compilation errors:\n" + errors);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(snapPanel,
                            "Compilation failed for: " + fileName + "\n\n"
                                    + truncate(errors, 300),
                            "Compile Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }

                System.out.println("[ChromeSD] Compilation successful, loading class...");

                // Load the compiled class
                String className = fileName.replace(".java", "");
                java.net.URLClassLoader loader = new java.net.URLClassLoader(
                        new java.net.URL[]{outputDir.toURI().toURL()},
                        this.getClass().getClassLoader()
                );

                Class<?> scriptClass = loader.loadClass(className);

                // Check if it implements ChromeScript
                if (!echelon.ui.snap.ChromeScript.class.isAssignableFrom(scriptClass)) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(snapPanel,
                            "Script must implement ChromeScript interface.\n\n"
                                    + "Add: implements echelon.ui.snap.ChromeScript",
                            "Script Error", JOptionPane.ERROR_MESSAGE));
                    return;
                }

                // Create context with the active tab's browser
                echelon.ui.snap.ScriptContext ctx = new echelon.ui.snap.ScriptContext(
                        activeTab.browser, activeTab.id, frame
                );

                // Instantiate and run
                echelon.ui.snap.ChromeScript script =
                        (echelon.ui.snap.ChromeScript) scriptClass.getDeclaredConstructor().newInstance();

                System.out.println("[ChromeSD] Executing: " + className + " on tab " + activeTab.id);
                script.run(ctx);

                System.out.println("[ChromeSD] Script finished: " + className);
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(snapPanel,
                        "Script completed: " + fileName,
                        "Script Done", JOptionPane.INFORMATION_MESSAGE));

            } catch (Exception ex) {
                ex.printStackTrace();
                String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(snapPanel,
                        "Script error: " + msg,
                        "Script Error", JOptionPane.ERROR_MESSAGE));
            }
        }, "script-runner-" + fileName).start();
    }

    private void refreshSnapTabList(DefaultListModel<String> model, JList<String> list) {
        SwingUtilities.invokeLater(() -> {
            int selectedIdx = tabbedPane.getSelectedIndex();
            model.clear();
            for (int i = 0; i < tabs.size(); i++) {
                TabInfo tab = tabs.get(i);
                String prefix = tab.incognito ? "\uD83D\uDD76 " : "";
                String display = prefix + truncate(tab.title, 35);
                model.addElement(display);
            }
            if (selectedIdx >= 0 && selectedIdx < model.size()) {
                list.setSelectedIndex(selectedIdx);
            }
        });
    }

    private TabInfo createTab(boolean incognito) {
        tabCounter++;
        String id = (incognito ? "Incognito-" : "Tab-") + tabCounter;
        CefBrowser browser = cefClient.createBrowser(HOME_URL, false, false);
        TabInfo tab = new TabInfo(id, browser, incognito);
        tabs.add(tab);

        String prefix = incognito ? "\uD83D\uDD76 " : "";
        tabbedPane.addTab(prefix + "New Tab", tab.uiComponent);
        int idx = tabbedPane.getTabCount() - 1;
        tabbedPane.setToolTipTextAt(idx, id);
        tabbedPane.setTabComponentAt(idx, createTabComponent(tab));
        tabbedPane.setSelectedIndex(idx);
        return tab;
    }

    private JPanel createTabComponent(TabInfo tab) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        panel.setOpaque(false);

        JLabel label = new JLabel(tab.incognito ? "\uD83D\uDD76 New Tab" : "New Tab");
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
        panel.add(label);

        JButton closeBtn = new JButton("\u2715");
        closeBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
        closeBtn.setMargin(new Insets(0, 2, 0, 2));
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setFocusable(false);
        closeBtn.setToolTipText("Close tab");
        closeBtn.addActionListener(e -> closeTab(tab));
        panel.add(closeBtn);

        tab.browser.getClient().addDisplayHandler(new CefDisplayHandlerAdapter() {
            @Override
            public void onTitleChange(CefBrowser browser, String title) {
                if (title != null && title.startsWith("__ECHELON_AF__:")) return;
                if (browser == tab.browser) {
                    SwingUtilities.invokeLater(() -> {
                        String pfx = tab.incognito ? "\uD83D\uDD76 " : "";
                        label.setText(pfx + truncate(title, 20));
                        label.setToolTipText(title);
                    });
                }
            }
        });
        return panel;
    }

    public void openNewTab(boolean incognito) { createTab(incognito); }

    private void closeTab(TabInfo tab) {
        int idx = tabs.indexOf(tab);
        if (idx < 0) return;
        tabs.remove(idx);
        tabbedPane.removeTabAt(idx);
        tab.browser.close(true);
        if (tabs.isEmpty()) openNewTab(false);
    }

    private void closeCurrentTab() {
        TabInfo tab = getActiveTab(); if (tab != null) closeTab(tab);
    }

    private void closeAllTabs() {
        List<TabInfo> toClose = new ArrayList<>(tabs);
        for (TabInfo t : toClose) { tabs.remove(t); t.browser.close(true); }
        tabbedPane.removeAll();
        openNewTab(false);
    }

    private void switchToTab(String tabId) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(tabId)) { tabbedPane.setSelectedIndex(i); return; }
        }
    }

    private void onTabChanged(ChangeEvent e) {
        TabInfo tab = getActiveTab();
        if (tab != null) {
            urlField.setText(tab.url != null ? tab.url : "");
            String prefix = tab.incognito ? "[Incognito] " : "";
            frame.setTitle("Chrome Browser \u2014 " + prefix + tab.title);
        }
    }

    private TabInfo getActiveTab() {
        int idx = tabbedPane.getSelectedIndex();
        return (idx >= 0 && idx < tabs.size()) ? tabs.get(idx) : null;
    }

    private TabInfo findTab(CefBrowser browser) {
        for (TabInfo tab : tabs) { if (tab.browser == browser) return tab; }
        return null;
    }

    // ====================== Context Menu Helpers ======================

    /** Copy text to the system clipboard. */
    private void copyToClipboard(String text) {
        if (text == null) return;
        java.awt.datatransfer.StringSelection sel =
                new java.awt.datatransfer.StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
    }

    /**
     * Open a URL in a brand-new ChromeSD instance (separate Main module).
     * Falls back to opening in a new tab if instantiation fails.
     */
    private void openInNewInstance(String url, boolean incognito) {
        try {
            // Spawn a new ChromeSD module instance
            Main newInstance = new Main();
            // Main's constructor calls buildUI + openNewTab, so the frame exists.
            // Access it through the module's standard show mechanism.
            newInstance.frame.setVisible(true);
            // Navigate the first tab to the target URL
            TabInfo firstTab = newInstance.getActiveTab();
            if (firstTab != null) {
                firstTab.browser.loadURL(url);
            }
            System.out.println("[ChromeSD] Opened new " +
                    (incognito ? "incognito " : "") + "instance for: " + url);
        } catch (Exception e) {
            System.err.println("[ChromeSD] Failed to open new instance, using new tab: " + e.getMessage());
            TabInfo newTab = createTab(incognito);
            newTab.browser.loadURL(url);
        }
    }

    /**
     * Show a file picker for scripts, then run the selected one.
     * Lists scripts from ChromeSD/scripts/ directory.
     */
    private void showScriptPicker(CefBrowser browser) {
        java.nio.file.Path scriptsDir = getScriptsDir();
        if (scriptsDir == null || !java.nio.file.Files.isDirectory(scriptsDir)) {
            JOptionPane.showMessageDialog(frame, "Scripts directory not found.",
                    "Run Script", JOptionPane.WARNING_MESSAGE);
            return;
        }

        java.io.File[] scripts = scriptsDir.toFile().listFiles(
                (dir, name) -> name.endsWith(".java"));

        if (scripts == null || scripts.length == 0) {
            JOptionPane.showMessageDialog(frame, "No scripts found in:\n" + scriptsDir,
                    "Run Script", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Build a simple list dialog
        String[] scriptNames = new String[scripts.length];
        for (int i = 0; i < scripts.length; i++) {
            scriptNames[i] = scripts[i].getName().replace(".java", "");
        }

        String selected = (String) JOptionPane.showInputDialog(frame,
                "Select a script to run on this page:",
                "Run Script",
                JOptionPane.PLAIN_MESSAGE, null,
                scriptNames, scriptNames[0]);

        if (selected != null) {
            for (java.io.File f : scripts) {
                if (f.getName().replace(".java", "").equals(selected)) {
                    runScript(f);
                    break;
                }
            }
        }
    }

    /**
     * Add a bookmark. Saves to ChromeSD/bookmarks.txt as "title|url" per line.
     */
    /**
     * Add a bookmark via the bookmarks bar.
     */
    private void addBookmark(String title, String url) {
        if (url == null || url.isEmpty()) return;
        if (bookmarksBar != null) {
            if (bookmarksBar.isBookmarked(url)) {
                JOptionPane.showMessageDialog(frame,
                        "Already bookmarked: " + title,
                        "Bookmark", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            bookmarksBar.addBookmark(title, url);
            System.out.println("[ChromeSD] Bookmarked: " + title + " \u2192 " + url);
            statusLabel.setText("\u2605 Bookmarked: " + title);
            Timer t = new Timer(3000, e -> statusLabel.setText(" "));
            t.setRepeats(false);
            t.start();
        }
    }

    /** Open JCEF DevTools in a separate Echelon window. */
    private void openDevTools(CefBrowser browser) {
        try {
            // JCEF DevTools API varies by version — try common signatures via reflection
            CefBrowser devTools = null;
            try {
                // Try getDevTools() — no args
                java.lang.reflect.Method m = browser.getClass().getMethod("getDevTools");
                devTools = (CefBrowser) m.invoke(browser);
            } catch (NoSuchMethodException e1) {
                try {
                    // Try getDevTools(Point) — some JCEF versions
                    java.lang.reflect.Method m = browser.getClass().getMethod("getDevTools", Point.class);
                    devTools = (CefBrowser) m.invoke(browser, new Point(0, 0));
                } catch (NoSuchMethodException e2) {
                    System.err.println("[ChromeSD] DevTools API not found on this JCEF build");
                }
            }

            if (devTools == null) {
                JOptionPane.showMessageDialog(frame,
                        "DevTools not available in this JCEF build.",
                        "Inspect", JOptionPane.WARNING_MESSAGE);
                return;
            }

            JFrame devFrame = echelon.ui.WindowFactory.create("DevTools", true);
            devFrame.setSize(900, 600);
            devFrame.setLocationRelativeTo(frame);
            devFrame.setLayout(new BorderLayout());
            devFrame.add(devTools.getUIComponent(), BorderLayout.CENTER);
            devFrame.setVisible(true);

            devFrame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    devFrame.dispose();
                }
            });
        } catch (Exception e) {
            System.err.println("[ChromeSD] Failed to open DevTools: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void navigateToUrl() {
        TabInfo tab = getActiveTab();
        if (tab == null) return;
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;

        if (!url.contains("://")) {
            if (url.contains(".") && !url.contains(" ")) {
                url = "https://" + url;
            } else {
                try {
                    url = "https://www.google.com/search?q="
                            + java.net.URLEncoder.encode(url, "UTF-8");
                } catch (java.io.UnsupportedEncodingException ex) { /* impossible */ }
            }
        }
        tab.browser.loadURL(url);
    }

    @Override
    public JPopupMenu buildBottomBarMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem newTab = new JMenuItem("New Tab");
        newTab.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        newTab.addActionListener(e -> { openNewTab(false); bringToFront(); });
        menu.add(newTab);

        JMenuItem newIncognito = new JMenuItem("New Incognito Tab");
        newIncognito.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        newIncognito.addActionListener(e -> { openNewTab(true); bringToFront(); });
        menu.add(newIncognito);

        menu.addSeparator();

        if (tabs.isEmpty()) {
            JMenuItem noTabs = new JMenuItem("No open tabs");
            noTabs.setFont(new Font("Segoe UI", Font.ITALIC, 13));
            noTabs.setEnabled(false);
            menu.add(noTabs);
        } else {
            JMenu openTabMenu = new JMenu("Open Tab");
            openTabMenu.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            TabInfo active = getActiveTab();
            for (TabInfo tab : tabs) {
                String label = (tab.incognito ? "\uD83D\uDD76 " : "") + truncate(tab.title, 35);
                JMenuItem item = new JMenuItem(label);
                item.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                if (tab == active) item.setFont(item.getFont().deriveFont(Font.BOLD));
                final String tabId = tab.id;
                item.addActionListener(e -> { switchToTab(tabId); bringToFront(); });
                openTabMenu.add(item);
            }
            menu.add(openTabMenu);
        }

        menu.addSeparator();
        if (!tabs.isEmpty()) {
            JMenuItem closeAll = new JMenuItem("Close All Tabs (" + tabs.size() + ")");
            closeAll.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            closeAll.addActionListener(e -> closeAllTabs());
            menu.add(closeAll);
            menu.addSeparator();
        }
        return menu;
    }

    @Override public void start() { frame.setVisible(true); }
    @Override public void bringToFront() {
        if (frame != null) { frame.setVisible(true); frame.toFront(); }
    }
    @Override public void hideModule() { if (frame != null) frame.setVisible(false); }
    @Override public void showModule() { if (frame != null) frame.setVisible(true); }
    @Override public boolean isVisible() { return frame != null && frame.isVisible(); }

    @Override
    protected void onClose() {
        // Dispose snap panel first
        if (snapPanel != null) {
            try { snapPanel.dispose(); } catch (Throwable ignore) {}
            snapPanel = null;
        }

        // Capture references before nulling out
        final List<TabInfo> tabsToClose = new ArrayList<>(tabs);
        final CefClient clientToDispose = cefClient;
        final JFrame frameToDispose = frame;

        // Null out immediately so any stale CEF callbacks become no-ops
        tabs.clear();
        cefClient = null;
        frame = null;

        // CEF teardown OFF the EDT to prevent native window corruption
        Thread cefCleanup = new Thread(() -> {
            for (TabInfo tab : tabsToClose) {
                try { tab.browser.close(true); } catch (Exception ignored) {}
            }
            try { Thread.sleep(100); } catch (InterruptedException ignored) {} // let CEF settle
            if (clientToDispose != null) {
                try { clientToDispose.dispose(); } catch (Exception ignored) {}
            }

            // UI cleanup back ON the EDT
            SwingUtilities.invokeLater(() -> {
                if (frameToDispose != null) frameToDispose.dispose();
            });
        }, "cef-cleanup");
        cefCleanup.setDaemon(true);
        cefCleanup.start();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 3) + "..." : s;
    }

    public static void main(String[] args) { new Main().start(); }
}
