import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * ChromeSD Bookmarks Bar — sits below the nav bar, shows bookmark buttons
 * and folder dropdown menus. Persists to ChromeSD/bookmarks.txt.
 *
 * <h3>File format (one entry per line):</h3>
 * <pre>
 *   title|url              → root-level bookmark
 *   foldername|title|url   → bookmark inside a folder
 * </pre>
 *
 * <p>Usage in Main.java:
 * <pre>
 *   bookmarksBar = new BookmarksBar(url -> navigateActiveTab(url));
 *   topPanel.add(bookmarksBar, BorderLayout.SOUTH);
 *   // After adding a bookmark:
 *   bookmarksBar.refresh();
 * </pre>
 */
public class BookmarksBar extends JPanel {

    /** A single bookmark entry. */
    static class BookmarkEntry {
        String folder; // null or "" = root level
        String title;
        String url;

        BookmarkEntry(String folder, String title, String url) {
            this.folder = (folder == null || folder.trim().isEmpty()) ? null : folder.trim();
            this.title = title;
            this.url = url;
        }

        boolean isInFolder() {
            return folder != null;
        }

        /** Serialize to file format. */
        String toLine() {
            String safeTitle = title.replace("|", "-");
            if (folder != null) {
                String safeFolder = folder.replace("|", "-");
                return safeFolder + "|" + safeTitle + "|" + url;
            }
            return safeTitle + "|" + url;
        }

        /** Parse from file format line. */
        static BookmarkEntry fromLine(String line) {
            if (line == null || line.trim().isEmpty()) return null;
            String[] parts = line.split("\\|", -1);
            if (parts.length == 2) {
                return new BookmarkEntry(null, parts[0].trim(), parts[1].trim());
            } else if (parts.length >= 3) {
                return new BookmarkEntry(parts[0].trim(), parts[1].trim(), parts[2].trim());
            }
            return null;
        }
    }

    // ======================== Fields ========================

    private final List<BookmarkEntry> bookmarks = new ArrayList<>();
    private final Consumer<String> onNavigate;    // callback: navigate to URL
    private final JPanel barContent;               // holds the bookmark buttons
    private Path bookmarksFile;

    // Styling
    private static final Font BTN_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final int MAX_LABEL = 22; // max chars per button label

    // ======================== Construction ========================

    /**
     * @param onNavigate callback invoked when a bookmark is clicked (receives URL)
     */
    public BookmarksBar(Consumer<String> onNavigate) {
        this.onNavigate = onNavigate;

        setLayout(new BorderLayout());
        setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(2, 6, 2, 6)
        ));

        barContent = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        barContent.setOpaque(false);

        // Wrap in a scroll pane for overflow (hidden scrollbar, horizontal scroll on drag)
        JScrollPane scroll = new JScrollPane(barContent,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);

        // Mouse wheel scrolls horizontally
        scroll.addMouseWheelListener(e -> {
            JScrollBar hBar = scroll.getHorizontalScrollBar();
            hBar.setValue(hBar.getValue() + e.getWheelRotation() * 40);
        });

        add(scroll, BorderLayout.CENTER);

        // "Add bookmark" star button on the right
        JButton starBtn = new JButton("\u2606"); // ☆
        starBtn.setFont(new Font("SansSerif", Font.PLAIN, 14));
        starBtn.setToolTipText("Bookmark this page (Ctrl+D)");
        starBtn.setMargin(new Insets(0, 4, 0, 4));
        starBtn.setFocusPainted(false);
        starBtn.setBorderPainted(false);
        starBtn.setContentAreaFilled(false);
        starBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        starBtn.addActionListener(e -> {
            if (addBookmarkCallback != null) addBookmarkCallback.run();
        });
        add(starBtn, BorderLayout.EAST);

        resolveBookmarksFile();
        load();
        rebuild();
    }

    // Callback for star button — set by Main.java to add current page
    private Runnable addBookmarkCallback;

    public void setAddBookmarkCallback(Runnable callback) {
        this.addBookmarkCallback = callback;
    }

    // ======================== Public API ========================

    /** Reload bookmarks from disk and rebuild the bar. */
    public void refresh() {
        load();
        rebuild();
    }

    /** Add a bookmark and refresh. */
    public void addBookmark(String title, String url) {
        addBookmark(null, title, url);
    }

    /** Add a bookmark to a specific folder and refresh. */
    public void addBookmark(String folder, String title, String url) {
        if (url == null || url.isEmpty()) return;

        // Duplicate check
        for (BookmarkEntry bm : bookmarks) {
            if (bm.url.equals(url)) return;
        }

        bookmarks.add(new BookmarkEntry(folder, title, url));
        save();
        rebuild();
    }

    /** Remove a bookmark by URL and refresh. */
    public void removeBookmark(String url) {
        bookmarks.removeIf(bm -> bm.url.equals(url));
        save();
        rebuild();
    }

    /** Check if a URL is bookmarked. */
    public boolean isBookmarked(String url) {
        for (BookmarkEntry bm : bookmarks) {
            if (bm.url.equals(url)) return true;
        }
        return false;
    }

    /** Get all unique folder names. */
    /** Get all unique folder names. */
    public List<String> getFolders() {
        Set<String> folders = new LinkedHashSet<>();
        for (BookmarkEntry bm : bookmarks) {
            if (bm.folder != null) folders.add(bm.folder);
        }
        return new ArrayList<>(folders);
    }

    /** Get a copy of all bookmark entries. */
    public List<BookmarkEntry> getAllEntries() {
        return new ArrayList<>(bookmarks);
    }

    /** Get the bookmark file path. */
    public Path getBookmarksFile() {
        return bookmarksFile;
    }

    // ======================== UI Rebuild ========================

    private void rebuild() {
        barContent.removeAll();

        // Separate root bookmarks and folders
        List<BookmarkEntry> rootBookmarks = new ArrayList<>();
        Map<String, List<BookmarkEntry>> folders = new LinkedHashMap<>();

        for (BookmarkEntry bm : bookmarks) {
            if (bm.isInFolder()) {
                folders.computeIfAbsent(bm.folder, k -> new ArrayList<>()).add(bm);
            } else {
                rootBookmarks.add(bm);
            }
        }

        // Root-level bookmarks first
        for (BookmarkEntry bm : rootBookmarks) {
            barContent.add(createBookmarkButton(bm));
        }

        // Then folder buttons
        for (Map.Entry<String, List<BookmarkEntry>> entry : folders.entrySet()) {
            barContent.add(createFolderButton(entry.getKey(), entry.getValue()));
        }

        // If empty, show hint
        if (bookmarks.isEmpty()) {
            JLabel hint = new JLabel("Bookmarks will appear here");
            hint.setFont(new Font("SansSerif", Font.ITALIC, 11));
            hint.setForeground(UIManager.getColor("Label.disabledForeground"));
            barContent.add(hint);
        }

        barContent.revalidate();
        barContent.repaint();
    }

    private JButton createBookmarkButton(BookmarkEntry bm) {
        String label = truncate(bm.title, MAX_LABEL);
        JButton btn = new JButton(label);
        btn.setFont(BTN_FONT);
        btn.setToolTipText(bm.title + "\n" + bm.url);
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Make it look flat / minimal
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setContentAreaFilled(true);
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setContentAreaFilled(false);
            }
        });

        // Left click → navigate
        btn.addActionListener(e -> {
            if (onNavigate != null) onNavigate.accept(bm.url);
        });

        // Middle click → open in new tab (handled by Main via onNavigate prefix)
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    if (onNavigate != null) onNavigate.accept("__NEW_TAB__:" + bm.url);
                }
            }
        });

        // Right-click context menu
        btn.setComponentPopupMenu(createBookmarkPopup(bm));

        return btn;
    }

    private JButton createFolderButton(String folderName, List<BookmarkEntry> contents) {
        JButton btn = new JButton("\uD83D\uDCC1 " + truncate(folderName, MAX_LABEL - 2));
        btn.setFont(BTN_FONT);
        btn.setToolTipText("Folder: " + folderName + " (" + contents.size() + " bookmarks)");
        btn.setMargin(new Insets(2, 6, 2, 6));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorderPainted(false);
        btn.setContentAreaFilled(false);
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setContentAreaFilled(true);
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setContentAreaFilled(false);
            }
        });

        btn.addActionListener(e -> {
            JPopupMenu popup = new JPopupMenu();

            for (BookmarkEntry bm : contents) {
                JMenuItem item = new JMenuItem(truncate(bm.title, 40));
                item.setFont(BTN_FONT);
                item.setToolTipText(bm.url);
                item.addActionListener(ev -> {
                    if (onNavigate != null) onNavigate.accept(bm.url);
                });
                popup.add(item);
            }

            popup.addSeparator();

            // "Rename folder" option
            JMenuItem renameItem = new JMenuItem("Rename Folder...");
            renameItem.setFont(BTN_FONT);
            renameItem.addActionListener(ev -> renameFolder(folderName));
            popup.add(renameItem);

            // "Delete folder" option
            JMenuItem deleteItem = new JMenuItem("Delete Folder");
            deleteItem.setFont(BTN_FONT);
            deleteItem.addActionListener(ev -> deleteFolder(folderName));
            popup.add(deleteItem);

            popup.show(btn, 0, btn.getHeight());
        });

        return btn;
    }

    // ======================== Context Menu ========================

    private JPopupMenu createBookmarkPopup(BookmarkEntry bm) {
        JPopupMenu popup = new JPopupMenu();

        JMenuItem open = new JMenuItem("Open");
        open.setFont(BTN_FONT);
        open.addActionListener(e -> { if (onNavigate != null) onNavigate.accept(bm.url); });
        popup.add(open);

        JMenuItem openNewTab = new JMenuItem("Open in New Tab");
        openNewTab.setFont(BTN_FONT);
        openNewTab.addActionListener(e -> {
            if (onNavigate != null) onNavigate.accept("__NEW_TAB__:" + bm.url);
        });
        popup.add(openNewTab);

        popup.addSeparator();

        JMenuItem edit = new JMenuItem("Edit...");
        edit.setFont(BTN_FONT);
        edit.addActionListener(e -> editBookmark(bm));
        popup.add(edit);

        // "Move to Folder" submenu
        JMenu moveMenu = new JMenu("Move to Folder");
        moveMenu.setFont(BTN_FONT);

        // Option: move to root
        if (bm.isInFolder()) {
            JMenuItem toRoot = new JMenuItem("(Bookmarks bar)");
            toRoot.setFont(BTN_FONT);
            toRoot.addActionListener(e -> {
                bm.folder = null;
                save();
                rebuild();
            });
            moveMenu.add(toRoot);
        }

        // Existing folders
        for (String folder : getFolders()) {
            if (folder.equals(bm.folder)) continue; // skip current folder
            JMenuItem folderItem = new JMenuItem(folder);
            folderItem.setFont(BTN_FONT);
            folderItem.addActionListener(e -> {
                bm.folder = folder;
                save();
                rebuild();
            });
            moveMenu.add(folderItem);
        }

        moveMenu.addSeparator();
        JMenuItem newFolder = new JMenuItem("New Folder...");
        newFolder.setFont(BTN_FONT);
        newFolder.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this,
                    "New folder name:", "New Folder", JOptionPane.PLAIN_MESSAGE);
            if (name != null && !name.trim().isEmpty()) {
                bm.folder = name.trim();
                save();
                rebuild();
            }
        });
        moveMenu.add(newFolder);

        popup.add(moveMenu);

        popup.addSeparator();

        JMenuItem delete = new JMenuItem("Delete");
        delete.setFont(BTN_FONT);
        delete.addActionListener(e -> {
            bookmarks.remove(bm);
            save();
            rebuild();
        });
        popup.add(delete);

        return popup;
    }

    // ======================== Edit / Rename / Delete ========================

    private void editBookmark(BookmarkEntry bm) {
        JTextField titleField = new JTextField(bm.title, 25);
        JTextField urlField = new JTextField(bm.url, 25);
        titleField.setFont(BTN_FONT);
        urlField.setFont(BTN_FONT);

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.add(new JLabel("Title:"));
        panel.add(titleField);
        panel.add(new JLabel("URL:"));
        panel.add(urlField);

        int result = JOptionPane.showConfirmDialog(this, panel,
                "Edit Bookmark", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String newTitle = titleField.getText().trim();
            String newUrl = urlField.getText().trim();
            if (!newTitle.isEmpty()) bm.title = newTitle;
            if (!newUrl.isEmpty()) bm.url = newUrl;
            save();
            rebuild();
        }
    }

    private void renameFolder(String oldName) {
        String newName = (String) JOptionPane.showInputDialog(this,
                "Rename folder:", "Rename Folder",
                JOptionPane.PLAIN_MESSAGE, null, null, oldName);
        if (newName != null && !newName.trim().isEmpty() && !newName.trim().equals(oldName)) {
            for (BookmarkEntry bm : bookmarks) {
                if (oldName.equals(bm.folder)) {
                    bm.folder = newName.trim();
                }
            }
            save();
            rebuild();
        }
    }

    private void deleteFolder(String folderName) {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete folder \"" + folderName + "\" and all its bookmarks?",
                "Delete Folder", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            bookmarks.removeIf(bm -> folderName.equals(bm.folder));
            save();
            rebuild();
        }
    }

    // ======================== Persistence ========================

    private void resolveBookmarksFile() {
        try {
            Path appsDir = echelon.util.DirectoryConfig.applicationsDir(true);
            bookmarksFile = appsDir.resolve("ChromeSD").resolve("bookmarks.txt");
            Files.createDirectories(bookmarksFile.getParent());
        } catch (Exception e) {
            Path fallback = Paths.get(System.getProperty("user.home"),
                    "Documents", "echelon", "desktop", "container",
                    "applications", "ChromeSD", "bookmarks.txt");
            try { Files.createDirectories(fallback.getParent()); } catch (IOException ignored) {}
            bookmarksFile = fallback;
        }
    }

    private void load() {
        bookmarks.clear();
        if (bookmarksFile == null || !Files.exists(bookmarksFile)) return;
        try {
            List<String> lines = Files.readAllLines(bookmarksFile);
            for (String line : lines) {
                BookmarkEntry bm = BookmarkEntry.fromLine(line);
                if (bm != null) bookmarks.add(bm);
            }
        } catch (Exception e) {
            System.err.println("[BookmarksBar] Failed to load: " + e.getMessage());
        }
    }

    private void save() {
        if (bookmarksFile == null) return;
        try {
            List<String> lines = new ArrayList<>();
            for (BookmarkEntry bm : bookmarks) {
                lines.add(bm.toLine());
            }
            Files.write(bookmarksFile, lines);
        } catch (Exception e) {
            System.err.println("[BookmarksBar] Failed to save: " + e.getMessage());
        }
    }

    // ======================== Helpers ========================

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "\u2026";
    }
}