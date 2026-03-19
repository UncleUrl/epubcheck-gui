import com.adobe.epubcheck.api.EpubCheck;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone GUI wrapper for EPUBCheck 5.2.1.
 *
 * Build and package with jpackage (see .github/workflows/build-windows.yml).
 * The resulting Windows installer bundles a JRE -- users need nothing pre-installed.
 */
public class EPUBCheckGUI extends JFrame {

    // Matches the summary line EPUBCheck always prints, e.g.:
    //   Messages: 0 fatals / 2 errors / 1 warnings / 0 infos
    private static final Pattern MSG_PATTERN = Pattern.compile(
            "Messages:\\s*(\\d+)\\s*fatals?\\s*/\\s*(\\d+)\\s*errors?\\s*/\\s*(\\d+)\\s*warnings?",
            Pattern.CASE_INSENSITIVE);

    private final JTextField filePathField;
    private final JButton    browseButton;
    private final JButton    checkButton;
    private final JTextArea  resultsArea;
    private final JLabel     statusLabel;
    private File selectedFile;

    // ── Construction ───────────────────────────────────────────────────

    public EPUBCheckGUI() {
        super("EPUBCheck 5.2.1");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(760, 560);
        setMinimumSize(new Dimension(600, 400));

        // Top panel: file path field + Browse + Check buttons
        filePathField = new JTextField();
        filePathField.setEditable(false);

        browseButton = new JButton("Browse for EPUB...");
        browseButton.addActionListener(e -> doBrowse());

        checkButton = new JButton("Check EPUB");
        checkButton.setEnabled(false);
        checkButton.addActionListener(e -> doCheck());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        btnPanel.add(browseButton);
        btnPanel.add(checkButton);

        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        topPanel.add(filePathField, BorderLayout.CENTER);
        topPanel.add(btnPanel, BorderLayout.EAST);

        // Scrollable monospaced results area
        resultsArea = new JTextArea();
        resultsArea.setEditable(false);
        resultsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(resultsArea);
        scrollPane.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 10, 0, 10),
                scrollPane.getBorder()));

        // Status label at the bottom
        statusLabel = new JLabel(" ");
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 10, 10, 10));

        add(topPanel,   BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        setLocationRelativeTo(null);
    }

    // ── File picker ────────────────────────────────────────────────────

    private void doBrowse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("EPUB files (*.epub)", "epub"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile();
            filePathField.setText(selectedFile.getAbsolutePath());
            checkButton.setEnabled(true);
            resultsArea.setText("");
            statusLabel.setText(" ");
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
    }

    // ── Validation ─────────────────────────────────────────────────────

    private void doCheck() {
        browseButton.setEnabled(false);
        checkButton.setEnabled(false);
        resultsArea.setText("Checking, please wait...\n");
        statusLabel.setText(" ");

        final File epub = selectedFile;

        new SwingWorker<String, Void>() {

            @Override
            protected String doInBackground() {
                // EpubCheck's default report writes to System.err.
                // Capture both stdout and stderr so nothing is missed.
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream capture = new PrintStream(baos, true, StandardCharsets.UTF_8);
                System.setOut(capture);
                System.setErr(capture);
                try {
                    new EpubCheck(epub).doValidate();
                } catch (Exception ex) {
                    capture.println("Exception during validation:");
                    ex.printStackTrace(capture);
                } finally {
                    capture.flush();
                    System.setOut(oldOut);
                    System.setErr(oldErr);
                }
                return baos.toString(StandardCharsets.UTF_8);
            }

            @Override
            protected void done() {
                browseButton.setEnabled(true);
                checkButton.setEnabled(true);
                try {
                    String output = get();
                    if (output == null || output.isBlank()) {
                        output = "(EPUBCheck produced no output -- the file may be valid.)";
                    }
                    resultsArea.setText(output);
                    resultsArea.setCaretPosition(0);
                    applyStatus(output);
                } catch (Exception ex) {
                    resultsArea.setText("Failed to retrieve results:\n" + ex.getMessage());
                    showStatus("Check failed.", Color.RED);
                }
            }
        }.execute();
    }

    // ── Status logic ───────────────────────────────────────────────────

    /**
     * Parses EPUBCheck's standard "Messages: X fatals / Y errors / Z warnings" summary
     * line for accurate status, with text-based fallbacks.
     */
    private void applyStatus(String output) {
        Matcher m = MSG_PATTERN.matcher(output);
        if (m.find()) {
            int fatals   = Integer.parseInt(m.group(1));
            int errors   = Integer.parseInt(m.group(2));
            int warnings = Integer.parseInt(m.group(3));
            if (fatals > 0) {
                showStatus("FATAL ERRORS found -- see details above.", Color.RED);
            } else if (errors > 0) {
                showStatus("ERRORS found -- see details above.", Color.RED);
            } else if (warnings > 0) {
                showStatus("WARNINGS found (no errors) -- see details above.", new Color(180, 90, 0));
            } else {
                showStatus("PASS -- No errors or warnings found.", new Color(0, 130, 0));
            }
            return;
        }
        // Fallback: no summary line found, scan for keywords
        if (output.contains("No errors or warnings")) {
            showStatus("PASS -- No errors or warnings found.", new Color(0, 130, 0));
        } else if (output.toLowerCase().contains("error")) {
            showStatus("ERRORS found -- see details above.", Color.RED);
        } else {
            showStatus("Check complete -- review output above.", Color.DARK_GRAY);
        }
    }

    private void showStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    // ── Entry point ────────────────────────────────────────────────────

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new EPUBCheckGUI().setVisible(true));
    }
}
