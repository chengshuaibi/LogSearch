package org.atguigu.plugintest;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NovelStatusBarWidget implements StatusBarWidget, StatusBarWidget.TextPresentation, Disposable {

    private final Project project;
    private String text = "📖 右键导入小说...";
    private List<String> lines = new ArrayList<>();
    private List<String> chapters = new ArrayList<>();
    private int index = 0;
    private boolean hidden = false;

    private final File booksFile = new File(System.getProperty("user.home"), ".novel_books.json");
    private final File bookmarksFile = new File(System.getProperty("user.home"), ".novel_bookmarks.json");

    private List<NovelRecord> books = new ArrayList<>();
    private Map<String, Map<String, Integer>> bookmarks = new HashMap<>();
    private NovelRecord currentBook = null;

    private final Gson gson = new Gson();

    private enum LastAction { LINE_TURN, CHAPTER_JUMP }
    private LastAction lastAction = LastAction.LINE_TURN;

    public static class NovelRecord {
        String path;
        int lastIndex;

        public NovelRecord() {}
        public NovelRecord(String path, int lastIndex) {
            this.path = path;
            this.lastIndex = lastIndex;
        }
    }

    public NovelStatusBarWidget(Project project) {
        this.project = project;
        loadBooks();
        loadBookmarks();

        if (!books.isEmpty()) {
            currentBook = books.get(books.size() - 1);
            importTxtFileSilent(new File(currentBook.path));
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(new KeyEventDispatcher() {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getID() != KeyEvent.KEY_PRESSED) return false;
                if (!e.isAltDown()) return false;

                switch (e.getKeyCode()) {
                    case KeyEvent.VK_NUMPAD5:  // Ctrl + 数字键盘5
                    case KeyEvent.VK_5:        // Ctrl + 上排数字5
                        turnLine(false);       // 上一行
                        return true;
                    case KeyEvent.VK_NUMPAD6:  // Ctrl + 数字键盘6
                    case KeyEvent.VK_6:        // Ctrl + 上排数字6
                        turnLine(true);        // 下一行
                        return true;
                    case KeyEvent.VK_NUMPAD3:  // Ctrl + 数字键盘3
                    case KeyEvent.VK_3:        // Ctrl + 上排数字3
                        toggleHidden();        // 隐藏/显示
                        return true;
                }
                return false;
            }
        });
    }

    private void turnLine(boolean forward) {
        if (lines.isEmpty() || hidden) return;
        if (lastAction == LastAction.CHAPTER_JUMP) {
            // 避免章节跳转后立即跳到下一章节
            lastAction = LastAction.LINE_TURN;
        } else {
            index = forward ? (index + 1) % lines.size() : (index - 1 + lines.size()) % lines.size();
        }
        text = lines.get(index);
        saveCurrentBookProgress();
        updateStatusBar();
    }

    private void updateStatusBar() {
        StatusBar statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project);
        if (statusBar != null) statusBar.updateWidget(ID());
    }

    public void toggleHidden() {
        hidden = !hidden;
        text = hidden ? "📚" : (lines.isEmpty() ? "📖 右键导入小说..." : lines.get(index));
        updateStatusBar();
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return e -> {
            if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                turnLine(true);
            } else if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
                showMenu((Component) e.getSource());
            }
        };
    }

    private void showMenu(Component parent) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem importTxt = new JMenuItem("📥 导入TXT小说");
        importTxt.addActionListener(ev -> importTxtFile());

        JMenuItem switchBook = new JMenuItem("📚 切换书籍/删除");
        switchBook.addActionListener(ev -> showBookList());

        JMenuItem saveBookmark = new JMenuItem("🔖 保存书签点");
        saveBookmark.addActionListener(ev -> saveBookmarkPoint());

        JMenuItem loadBookmark = new JMenuItem("📜 查看书签点");
        loadBookmark.addActionListener(ev -> showBookmarkList());

        JMenuItem chapterList = new JMenuItem("📖 章节列表");
        chapterList.addActionListener(ev -> showChapterList());

        menu.add(importTxt);
        menu.add(switchBook);
        menu.add(saveBookmark);
        menu.add(loadBookmark);
        menu.addSeparator();
        menu.add(chapterList);

        menu.show(parent, 0, parent.getHeight());
    }

    private void importTxtFile() {
        JFileChooser chooser = new JFileChooser();
        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            importTxtFileSilent(file);
            currentBook = addOrUpdateBook(file.getAbsolutePath());
            saveBooks();
            JOptionPane.showMessageDialog(null, "导入成功，共 " + lines.size() + " 行");
        }
    }

    private void importTxtFileSilent(File file) {
        String charset = "GBK"; // 根据文件实际编码改
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), charset))) {

            lines.clear();
            chapters.clear();

            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                lines.add(line);
                if (line.matches("第.{1,9}[章回节卷].*")) {
                    chapters.add(line + "（行号: " + lineNum + "）");
                }
                lineNum++;
            }

            if (!lines.isEmpty() && currentBook != null) {
                index = Math.min(currentBook.lastIndex, lines.size() - 1);
                text = lines.get(index);
            } else {
                text = "📖 小说为空";
            }

        } catch (IOException ex) {
            text = "📖 小说读取失败";
        }
    }


    private NovelRecord addOrUpdateBook(String path) {
        for (NovelRecord b : books) if (b.path.equals(path)) return b;
        NovelRecord newBook = new NovelRecord(path, 0);
        books.add(newBook);
        return newBook;
    }

    private void saveCurrentBookProgress() {
        if (currentBook != null) {
            currentBook.lastIndex = index;
            saveBooks();
        }
    }

    private void saveBooks() {
        try (FileWriter fw = new FileWriter(booksFile)) {
            gson.toJson(books, fw);
        } catch (IOException ignored) {}
    }

    private void loadBooks() {
        if (!booksFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(booksFile))) {
            Type type = new TypeToken<List<NovelRecord>>() {}.getType();
            List<NovelRecord> list = gson.fromJson(r, type);
            if (list != null) books = list;
        } catch (IOException ignored) {}
    }

    private void saveBookmarks() {
        try (FileWriter fw = new FileWriter(bookmarksFile)) {
            gson.toJson(bookmarks, fw);
        } catch (IOException ignored) {}
    }

    private void loadBookmarks() {
        if (!bookmarksFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(bookmarksFile))) {
            Type type = new TypeToken<Map<String, Map<String, Integer>>>() {}.getType();
            Map<String, Map<String, Integer>> map = gson.fromJson(r, type);
            if (map != null) bookmarks = map;
        } catch (IOException ignored) {}
    }

    // -------------------- 章节管理 --------------------
    private void showChapterList() {
        if (chapters.isEmpty()) {
            JOptionPane.showMessageDialog(null, "未识别到章节标题（请确保章节行以“第X章”开头）");
            return;
        }

        JList<String> list = new JList<>(chapters.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(400, chapters.size() * 25)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "章节列表", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JButton jumpButton = new JButton("跳转");
        jumpButton.addActionListener(e -> {
            int selected = list.getSelectedIndex();
            if (selected >= 0) jumpToChapter(selected);
            dialog.dispose();
        });

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int selected = list.getSelectedIndex();
                    if (selected >= 0) {
                        jumpToChapter(selected);
                        dialog.dispose();
                    }
                }
            }
        });

        dialog.add(jumpButton, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private void jumpToChapter(int selected) {
        if (selected < 0 || selected >= chapters.size()) return;
        String chapter = chapters.get(selected);
        int pos = extractLineNumber(chapter);
        if (pos >= 0 && pos < lines.size()) {
            index = pos;
            text = lines.get(index);
            lastAction = LastAction.CHAPTER_JUMP;
            saveCurrentBookProgress();
            updateStatusBar();
        }
    }

    private int extractLineNumber(String chapter) {
        if (chapter == null) return -1;
        try {
            Pattern p = Pattern.compile("行号\\s*:\\s*(\\d+)");
            Matcher m = p.matcher(chapter);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // -------------------- 书籍与书签逻辑 --------------------
    private void showBookList() {
        if (books.isEmpty()) {
            JOptionPane.showMessageDialog(null, "没有已导入的书籍");
            return;
        }

        String[] bookNames = books.stream().map(b -> new File(b.path).getName()).toArray(String[]::new);
        JList<String> list = new JList<>(bookNames);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(Integer.valueOf(bookNames.length)- 1);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(300, bookNames.length * 30)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "切换书籍", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel();

        JButton switchButton = new JButton("切换");
        switchButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < books.size()) {
                currentBook = books.get(selectedIndex);
                importTxtFileSilent(new File(currentBook.path));
                updateStatusBar();
                dialog.dispose();
            }
        });
        buttonPanel.add(switchButton);

        JButton deleteButton = new JButton("删除");
        deleteButton.addActionListener(e -> {
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0 && selectedIndex < books.size()) {
                NovelRecord toDelete = books.remove(selectedIndex);
                if (currentBook != null && currentBook.path.equals(toDelete.path)) {
                    currentBook = null;
                    lines.clear();
                    chapters.clear();
                    index = 0;
                    text = "📖 右键导入小说...";
                }
                saveBooks();
                updateStatusBar();
                dialog.dispose();
            }
        });
        buttonPanel.add(deleteButton);

        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private void saveBookmarkPoint() {
        if (currentBook == null) return;
        String name = JOptionPane.showInputDialog(null, "书签名称：");
        if (name != null && !name.trim().isEmpty()) {
            bookmarks.computeIfAbsent(currentBook.path, k -> new HashMap<>()).put(name.trim(), index);
            saveBookmarks();
            JOptionPane.showMessageDialog(null, "书签点已保存！");
        }
    }

    private void showBookmarkList() {
        if (bookmarks.isEmpty()) {
            JOptionPane.showMessageDialog(null, "没有保存的书签点");
            return;
        }

        List<String> displayList = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : bookmarks.entrySet()) {
            String fileName = new File(entry.getKey()).getName();
            for (Map.Entry<String, Integer> bm : entry.getValue().entrySet()) {
                displayList.add(fileName + " - " + bm.getKey());
                keys.add(entry.getKey() + "|" + bm.getKey());
            }
        }

        JList<String> list = new JList<>(displayList.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setPreferredSize(new Dimension(400, Math.min(300, displayList.size() * 30)));

        JDialog dialog = new JDialog(com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project), "书签列表", true);
        dialog.setLayout(new BorderLayout());
        dialog.add(scrollPane, BorderLayout.CENTER);

        JButton goButton = new JButton("跳转");
        goButton.addActionListener(e -> {
            int idx = list.getSelectedIndex();
            if (idx >= 0) {
                String[] parts = keys.get(idx).split("\\|");
                String path = parts[0], bmName = parts[1];
                currentBook = books.stream().filter(b -> b.path.equals(path)).findFirst().orElse(null);
                if (currentBook != null) {
                    importTxtFileSilent(new File(currentBook.path));
                    index = bookmarks.get(path).get(bmName);
                    text = lines.get(index);
                    updateStatusBar();
                }
                dialog.dispose();
            }
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.add(goButton);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    @Override
    public @NotNull String ID() { return "NovelStatusBarWidget"; }

    @Override
    public @Nullable WidgetPresentation getPresentation() { return this; }

    @Override
    public void install(@NotNull StatusBar statusBar) {}

    @Override
    public void dispose() {}

    @Override
    public @NotNull String getText() { return text; }

    @Override
    public float getAlignment() {
        return Component.LEFT_ALIGNMENT; // 左对齐
    }

    @Nullable
    @Override
    public String getTooltipText() { return "小说阅读器"; }
}
