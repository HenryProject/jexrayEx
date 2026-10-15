package com.jexray.jadx.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import com.jexray.jadx.export.ProjectExporter;
import com.jexray.jadx.export.ProjectExporter.Layout;

/**
 * The "Export Project" dialog of the Native View: pick a folder, pick which of the two views to
 * include, pick a layout -- one file per library ({@link Layout#SINGLE_FILES}, the default)
 * or a folder per library mirroring the exact groups the All Functions browser shows
 * ({@code JNI methods} / {@code Functions} / {@code Exports} / {@code Imports}) with one file per
 * function ({@link Layout#SPLIT}) -- and check which libraries to export: every library found in
 * the APK is listed and checked by default, so exporting a subset is a matter of unchecking the
 * rest. Either way each file holds Ghidra pseudocode ({@code .c}) and/or disassembly ({@code .asm}),
 * UTF-8.
 *
 * <p>Owns only the UI: the actual export runs on a worker thread the caller starts when
 * {@link Runner#start} is handed the choices, and reports back through {@link #progress} and
 * {@link #finished} (callable from any thread -- both hop to the EDT themselves). While an export
 * is running the dialog cannot be dismissed by accident: closing it (or Escape) asks the run to
 * cancel instead, and the run stays cancellable between any two functions. A clean run closes the
 * dialog itself the moment it finishes (honouring the optional "open the export folder when
 * finished" checkbox on the way out -- the same summary is also flashed in the Native View's status
 * bar); a cancelled or partly failed run stays open, so what did and didn't make it out can
 * actually be read there.
 */
public class ExportDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	/** Starts one export run; see the class javadoc for the reporting contract. */
	@FunctionalInterface
	public interface Runner {
		void start(List<ProjectExporter.Library> selected, ProjectExporter.Options options,
				AtomicBoolean cancelled, ExportDialog dialog);
	}

	private final JTextField dirField = new JTextField();
	private final JCheckBox pseudoCheck = new JCheckBox("Pseudocode (.c)", true);
	private final JCheckBox disasmCheck = new JCheckBox("Disassembly (.asm)", true);
	private final JRadioButton singleRadio =
			new JRadioButton(Layout.SINGLE_FILES.label() + " — reads straight through", true);
	private final JRadioButton splitRadio =
			new JRadioButton(Layout.SPLIT.label() + " — like the All Functions tree");
	private final JCheckBox openWhenDoneCheck = new JCheckBox("Open the export folder when finished");
	private final JPanel cards = new JPanel(new CardLayout());
	private final JProgressBar bar = new JProgressBar(0, 100);
	private final JLabel progressLabel = new JLabel(" ");
	private final JButton exportButton = new JButton("Export");
	private final JButton browseButton = new JButton("Browse…");
	private final JButton cancelExportButton = new JButton("Cancel Export");
	private final JButton openFolderButton = new JButton("Open Folder");
	private final JButton closeButton = new JButton("Close");

	private final JLabel libCountLabel = new JLabel(" ");
	private final java.awt.Color defaultLibCountColor = libCountLabel.getForeground();
	/** One box per entry of {@link #libraries}, same index -- checked by default. */
	private final List<JCheckBox> libChecks = new ArrayList<>();

	private final UiPrefs prefs;
	private final List<ProjectExporter.Library> libraries;
	private final Runner runner;
	private final AtomicBoolean cancelled = new AtomicBoolean();

	private volatile boolean running;
	private Path resultDir;

	public ExportDialog(JFrame parent, List<ProjectExporter.Library> libraries, Runner runner) {
		this(parent, libraries, runner, new UiPrefs());
	}

	ExportDialog(JFrame parent, List<ProjectExporter.Library> libraries, Runner runner, UiPrefs prefs) {
		super(parent, "Jexray - Export Project", true);
		this.runner = runner;
		this.prefs = prefs;
		this.libraries = libraries;

		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); // a running export is cancelled, not orphaned
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				onDismiss();
			}
		});
		KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(esc, "jexray-export-esc");
		getRootPane().getActionMap().put("jexray-export-esc", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				onDismiss();
			}
		});

		// body width= keeps the window a focused form instead of one long banner line: the
		// intro wraps, and the path row below sets the real width floor.
		JLabel intro = new JLabel("<html><body width='560'>"
				+ "<b>Save the analyzed native code to a folder of your choice.</b><br>"
				+ "Write decompiled code (C pseudocode), disassembly, or both — for every "
				+ "library you check below. Every library in this APK starts checked; "
				+ "uncheck the ones you don't want.</body></html>");

		dirField.setToolTipText("Where the exported files go — created if it does not exist yet");
		browseButton.addActionListener(e -> chooseDir());
		JPanel dirRow = new JPanel(new BorderLayout(6, 0));
		dirRow.add(new JLabel("Save to:"), BorderLayout.WEST);
		dirRow.add(dirField, BorderLayout.CENTER);
		dirRow.add(browseButton, BorderLayout.EAST);

		pseudoCheck.setToolTipText("Decompiled C pseudocode — written as <function>.c");
		disasmCheck.setToolTipText("Disassembly — written as <function>.asm");
		JPanel contentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		contentRow.setOpaque(false);
		contentRow.add(new JLabel("Write:"));
		contentRow.add(pseudoCheck);
		contentRow.add(disasmCheck);

		// Layout: how the chosen content is arranged on disk. Single files is the default --
		// it reads straight through in an editor; the split layout browses like the tree.
		ButtonGroup layoutGroup = new ButtonGroup();
		layoutGroup.add(singleRadio);
		layoutGroup.add(splitRadio);
		singleRadio.setToolTipText("One .c / .asm file per library, every function preceded "
				+ "by a header (symbol, address, group)");
		splitRadio.setToolTipText("A folder per library with a file per function — mirrors "
				+ "exactly what the All Functions tree shows");
		singleRadio.setOpaque(false);
		splitRadio.setOpaque(false);
		JRadioButton[] radios = { singleRadio, splitRadio };
		if (Layout.SPLIT.name().equals(prefs.loadExportLayout())) {
			splitRadio.setSelected(true);
		}
		JPanel layoutCol = new JPanel(new GridLayout(radios.length, 1));
		layoutCol.setOpaque(false);
		for (JRadioButton radio : radios) {
			layoutCol.add(radio);
		}
		JPanel layoutRow = new JPanel(new BorderLayout(6, 0));
		layoutRow.add(new JLabel("Layout:"), BorderLayout.WEST);
		layoutRow.add(layoutCol, BorderLayout.CENTER);

		// Libraries: all listed, all checked -- excluding is a matter of unchecking, and
		// All/None settle the many-libraries case in one click. Listing order matches the
		// Native View's library list so the two views read the same way.
		JPanel libList = new JPanel();
		libList.setOpaque(false);
		libList.setLayout(new BoxLayout(libList, BoxLayout.Y_AXIS));
		for (ProjectExporter.Library lib : libraries) {
			JCheckBox box = new JCheckBox(lib.displayName(), true);
			box.setToolTipText(lib.soId());
			box.addActionListener(e -> updateLibCount());
			libChecks.add(box);
			libList.add(box);
		}
		updateLibCount();
		JButton allButton = new JButton("All");
		allButton.setToolTipText("Check every library");
		allButton.addActionListener(e -> setAllLibraries(true));
		JButton noneButton = new JButton("None");
		noneButton.setToolTipText("Uncheck every library, then tick just the ones you want");
		noneButton.addActionListener(e -> setAllLibraries(false));
		JPanel libButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		libButtons.setOpaque(false);
		libButtons.add(allButton);
		libButtons.add(noneButton);
		JPanel libsHeader = new JPanel(new BorderLayout(6, 0));
		libsHeader.add(new JLabel("Libraries to export:"), BorderLayout.WEST);
		libsHeader.add(libCountLabel, BorderLayout.CENTER);
		libsHeader.add(libButtons, BorderLayout.EAST);
		JScrollPane libScroll = new JScrollPane(libList);
		int listRows = Math.min(Math.max(libraries.size(), 1), 6);
		libScroll.setPreferredSize(new Dimension(0, listRows * 22 + 8));
		JPanel libsPanel = new JPanel(new BorderLayout(0, 4));
		libsPanel.add(libsHeader, BorderLayout.NORTH);
		libsPanel.add(libScroll, BorderLayout.CENTER);

		openWhenDoneCheck.setOpaque(false);
		openWhenDoneCheck.setSelected(prefs.loadExportOpenWhenDone());
		openWhenDoneCheck.setToolTipText("Opens the exported folder in your file manager "
				+ "as soon as the export finishes successfully");
		JLabel note = new JLabel("<html>Libraries not analyzed yet are analyzed first — big "
				+ "ones can take a while. Errors never abort the export; every detail is "
				+ "recorded in <b>jexray-project.txt</b> inside the export folder.</html>");
		note.setEnabled(false);
		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		south.add(openWhenDoneCheck, BorderLayout.NORTH);
		south.add(note, BorderLayout.SOUTH);

		JPanel options = new JPanel(new BorderLayout(0, 8));
		options.setBorder(new EmptyBorder(0, 0, 8, 0));
		JPanel top = new JPanel(new GridLayout(3, 1, 0, 6));
		top.setOpaque(false);
		top.add(dirRow);
		top.add(contentRow);
		top.add(layoutRow);
		options.add(top, BorderLayout.NORTH);
		options.add(libsPanel, BorderLayout.CENTER);
		options.add(south, BorderLayout.SOUTH);

		bar.setStringPainted(true);
		bar.setString("Preparing…");
		bar.setPreferredSize(new Dimension(10, 24));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		cancelExportButton.addActionListener(e -> requestCancel());
		progressLabel.setAlignmentX(LEFT_ALIGNMENT);
		bar.setAlignmentX(LEFT_ALIGNMENT);
		cancelExportButton.setAlignmentX(LEFT_ALIGNMENT);
		JPanel progress = new JPanel();
		progress.setOpaque(false);
		progress.setLayout(new BoxLayout(progress, BoxLayout.Y_AXIS));
		progress.add(Box.createVerticalGlue());
		progress.add(progressLabel);
		progress.add(Box.createVerticalStrut(8));
		progress.add(bar);
		progress.add(Box.createVerticalStrut(8));
		progress.add(cancelExportButton);
		progress.add(Box.createVerticalGlue());

		cards.add(options, "options");
		cards.add(progress, "progress");

		exportButton.addActionListener(e -> startExport());
		openFolderButton.setVisible(false);
		openFolderButton.addActionListener(e -> openFolder());
		closeButton.addActionListener(e -> onDismiss());
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(openFolderButton);
		buttons.add(exportButton);
		buttons.add(closeButton);

		JPanel content = new JPanel(new BorderLayout(8, 8));
		content.setBorder(new EmptyBorder(12, 12, 12, 12));
		content.add(intro, BorderLayout.NORTH);
		content.add(cards, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);

		dirField.setText(prefs.loadExportDir());
		pack();
		// A focused form, not a banner: width from the (wrapped) content, height with real
		// room for the library list and a comfortable progress card.
		setSize(new Dimension(Math.max(600, Math.min(getPreferredSize().width, 760)),
				Math.max(560, getPreferredSize().height)));
		setMinimumSize(new Dimension(520, 480));
		if (parent != null) {
			setLocationRelativeTo(parent);
		}
	}

	// ------------------------------------------------------------------ run lifecycle

	/** EDT only. Validate the choices, flip to the progress card, and hand off to the runner. */
	private void startExport() {
		if (running) {
			return;
		}
		Path dir;
		try {
			String text = dirField.getText().trim();
			if (text.isEmpty()) {
				throw new java.nio.file.InvalidPathException(text, "empty");
			}
			dir = Paths.get(text);
		} catch (RuntimeException e) {
			warn("That is not a valid folder path:\n" + dirField.getText());
			return;
		}
		if (!pseudoCheck.isSelected() && !disasmCheck.isSelected()) {
			warn("Pick at least one of Pseudocode / Disassembly to export.");
			return;
		}
		List<ProjectExporter.Library> selected = selectedLibraries();
		if (selected.isEmpty()) {
			warn("Check at least one library to export.");
			return;
		}
		prefs.saveExportDir(dir.toString());
		prefs.saveExportLayout(selectedLayout().name());
		prefs.saveExportOpenWhenDone(openWhenDoneCheck.isSelected());
		running = true;
		cancelled.set(false);
		cancelExportButton.setEnabled(true);
		exportButton.setEnabled(false);
		bar.setIndeterminate(true);
		bar.setString("Preparing…");
		progressLabel.setText("Exporting " + selected.size() + " librar"
				+ (selected.size() == 1 ? "y" : "ies") + " to " + dir + " …");
		showCard("progress");
		runner.start(selected, new ProjectExporter.Options(dir, pseudoCheck.isSelected(),
				disasmCheck.isSelected(), selectedLayout()), cancelled, this);
	}

	/** The layout the radios currently offer; single files unless the split one is picked. */
	private Layout selectedLayout() {
		return splitRadio.isSelected() ? Layout.SPLIT : Layout.SINGLE_FILES;
	}

	/** The libraries whose boxes are checked, in listing order. */
	private List<ProjectExporter.Library> selectedLibraries() {
		List<ProjectExporter.Library> out = new ArrayList<>();
		for (int i = 0; i < libraries.size(); i++) {
			if (libChecks.get(i).isSelected()) {
				out.add(libraries.get(i));
			}
		}
		return out;
	}

	private void setAllLibraries(boolean selected) {
		for (JCheckBox box : libChecks) {
			box.setSelected(selected);
		}
		updateLibCount();
	}

	/** Keep the "N of M selected" line truthful; an empty selection is worth noticing early. */
	private void updateLibCount() {
		int selected = 0;
		for (JCheckBox box : libChecks) {
			if (box.isSelected()) {
				selected++;
			}
		}
		libCountLabel.setText(selected + " of " + libraries.size() + " selected");
		libCountLabel.setToolTipText(selected == 0
				? "Nothing is checked — the export needs at least one library" : null);
		libCountLabel.setForeground(selected == 0 ? java.awt.Color.RED : defaultLibCountColor);
	}

	/**
	 * One progress sample from the worker thread. Coalesced on the EDT: a big library reports once
	 * per function, and re-setting an identical bar value/label would still pay a repaint each
	 * time, so only an actual change is pushed.
	 */
	public void progress(ProjectExporter.Progress p) {
		onEdt(() -> {
			int pct = p.overallPercent();
			if (pct < 0) {
				if (!bar.isIndeterminate()) {
					bar.setIndeterminate(true);
				}
				bar.setString("Analyzing " + p.libraryName() + "…");
			} else {
				if (bar.isIndeterminate()) {
					bar.setIndeterminate(false);
				}
				if (bar.getValue() != pct) {
					bar.setValue(pct);
				}
				String text = pct + "%";
				if (!text.equals(bar.getString())) {
					bar.setString(text);
				}
			}
			String line = describe(p);
			if (!line.equals(progressLabel.getText())) {
				progressLabel.setText(line);
			}
		});
	}

	/** The run is over (finished, failed to run, or cancelled) -- callable from any thread. */
	public void finished(ProjectExporter.Result result) {
		onEdt(() -> {
			running = false;
			resultDir = result.rootDir();
			bar.setIndeterminate(false);
			if (!result.cancelled()) {
				bar.setValue(100);
				bar.setString("100%");
			}
			progressLabel.setText("<html>" + escapeHtml(result.summary()) + "</html>");
			cancelExportButton.setEnabled(false);
			boolean hasFolder = Files.isDirectory(result.rootDir());
			openFolderButton.setVisible(hasFolder);
			closeButton.setText("Close");
			// A clean run closes itself right away -- the same summary is flashed in the Native
			// View's status bar and written to the manifest -- honouring "open the export folder"
			// on the way out. A cancelled or partly failed run stays open, so what did and didn't
			// make it out can actually be read here.
			boolean clean = !result.cancelled()
					&& result.libraryResults().stream().noneMatch(r -> r.failed());
			if (clean) {
				if (openWhenDoneCheck.isSelected() && hasFolder && !openFolder()) {
					return; // could not open it -- stay open, with the reason on screen
				}
				dispose();
			}
		});
	}

	private static String describe(ProjectExporter.Progress p) {
		String lib = "Library " + (p.libIndex() + 1) + "/" + p.libCount() + ": " + p.libraryName();
		if (!"exporting".equals(p.phase())) {
			return lib + " — analyzing (Ghidra is running; large libraries can take a while)";
		}
		if (p.totalFunctions() <= 0) {
			return lib + " — listing functions…";
		}
		return lib + " — exporting function " + Math.max(1, p.doneFunctions()) + " / " + p.totalFunctions();
	}

	// ------------------------------------------------------------------ chrome

	/** Flip between the "options" card and the "progress" card. */
	private void showCard(String name) {
		((CardLayout) cards.getLayout()).show(cards, name);
	}

	private void requestCancel() {
		if (!running) {
			return;
		}
		cancelled.set(true);
		cancelExportButton.setEnabled(false);
		progressLabel.setText("Cancelling — finishing the current function…");
	}

	/** Close the dialog, or -- while an export is running -- ask that export to cancel instead. */
	private void onDismiss() {
		if (running) {
			requestCancel();
			return;
		}
		dispose();
	}

	private void chooseDir() {
		JFileChooser chooser = new JFileChooser(dirField.getText().trim());
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose a folder for the exported project");
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			dirField.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	/** Open the export folder in the OS file manager; false (with a note) if that failed. */
	private boolean openFolder() {
		if (resultDir == null) {
			return false;
		}
		try {
			java.awt.Desktop.getDesktop().open(resultDir.toFile());
			return true;
		} catch (Exception e) {
			progressLabel.setText("<html>Could not open " + escapeHtml(resultDir.toString())
					+ " — open it manually.</html>");
			return false;
		}
	}

	private void warn(String message) {
		JOptionPane.showMessageDialog(this, message, "Jexray — Export Project",
				JOptionPane.WARNING_MESSAGE);
	}

	private static String escapeHtml(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static void onEdt(Runnable r) {
		if (SwingUtilities.isEventDispatchThread()) {
			r.run();
		} else {
			SwingUtilities.invokeLater(r);
		}
	}
}
