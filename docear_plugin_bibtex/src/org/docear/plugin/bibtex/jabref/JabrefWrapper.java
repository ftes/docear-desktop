package org.docear.plugin.bibtex.jabref;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;

import net.sf.jabref.BasePanel;
import net.sf.jabref.BibtexDatabase;
import net.sf.jabref.BibtexEntry;
import net.sf.jabref.BibtexFields;
import net.sf.jabref.Globals;
import net.sf.jabref.JabRef;
import net.sf.jabref.JabRefFrame;
import net.sf.jabref.Util;
import net.sf.jabref.export.SaveDatabaseAction;
import net.sf.jabref.export.SaveSession;
import net.sf.jabref.external.FileLinksUpgradeWarning;
import net.sf.jabref.imports.CheckForNewEntryTypesAction;
import net.sf.jabref.imports.OpenDatabaseAction;
import net.sf.jabref.imports.ParserResult;
import net.sf.jabref.imports.PostOpenAction;

import org.docear.plugin.bibtex.ReferencesController;
import org.docear.plugin.bibtex.actions.DocearHandleDuplicateWarning;
import org.docear.plugin.bibtex.actions.DocearTransformZoteroPathsAction;
import org.docear.plugin.bibtex.actions.FilePathValidatorAction;
import org.docear.plugin.bibtex.actions.HandleDuplicateKeys;
import org.docear.plugin.bibtex.listeners.MapViewListener;
import org.docear.plugin.core.DocearController;
import org.docear.plugin.core.event.DocearEvent;
import org.docear.plugin.core.event.DocearEventType;
import org.docear.plugin.core.logger.DocearLogEvent;
import org.freeplane.core.resources.ResourceController;
import org.freeplane.core.ui.components.UITools;
import org.freeplane.core.util.Compat;
import org.freeplane.core.util.LogUtils;
import org.freeplane.core.util.TextUtils;
import org.freeplane.features.mode.Controller;
import org.freeplane.features.ui.IMapViewChangeListener;
import org.swingplus.JHyperlink;

public class JabrefWrapper extends JabRef implements IMapViewChangeListener {

	private static final int MAX_TRY_OPEN = 5;

	private static ArrayList<PostOpenAction> postOpenActions = new ArrayList<PostOpenAction>();

	static {
		postOpenActions.add(new DocearTransformZoteroPathsAction());
		// bibtex files exported by mendeley do not contain leading "/" for
		// absolute paths so we do not know if
		// the file contaions relative paths or absolute paths
		postOpenActions.add(new FilePathValidatorAction());
		// Add the action for checking for new custom entry types loaded from
		// the bib file:
		postOpenActions.add(new CheckForNewEntryTypesAction());
		// Add the action for the new external file handling system in version
		// 2.3:
		postOpenActions.add(new FileLinksUpgradeWarning());
		// Add the action for warning about and handling duplicate BibTeX keys:
		//postOpenActions.add(new HandleDuplicateWarnings());
		//DOCEAR: don't warn, just resolve --> #464
		if (ResourceController.getResourceController().getBooleanProperty("docear.reference_manager.resolve_duplicate_keys")) {
		    postOpenActions.add(new HandleDuplicateKeys());
		}
		else {
		    postOpenActions.add(new DocearHandleDuplicateWarning());
		}
		
	}

	private static final MapViewListener mapViewListener = new MapViewListener();
	
	private Map<File, JabRefBaseHandle> baseHandles = new HashMap<File, JabRefBaseHandle>();

	public JabrefWrapper(JFrame frame) {
		super(frame);		
		registerListeners();
		
		this.jrf.getPreferences().put("generateKeysBeforeSaving", "true");
		this.jrf.getPreferences().put("avoidOverwritingKey", "true");
		this.jrf.addJabRefEventListener(new JabrefChangeEventListener());
		this.jrf.setBorder(new LineBorder(Color.LIGHT_GRAY, 1));
	}

	public JabRefFrame getJabrefFrame() {
		return this.jrf;
	}
	
	public JPanel getJabrefFramePanel() {
		return this.jrf;
	}
	
	public String getLocalizedColumnName(String s) {		
		String disName = BibtexFields.getFieldDisplayName(s);
        if (disName != null)
            return disName;
        else
            return Util.nCase(s);
	}

	private void registerListeners() {
		Controller.getCurrentController().getMapViewManager().addMapViewChangeListener(this);
		SwingUtilities.invokeLater(new Runnable() {
			public void run() {
				synchronized (Controller.getCurrentModeController().getMapController()) {
					
					Controller.getCurrentModeController().getMapController().addNodeSelectionListener(mapViewListener);
				}
			}
		});
	}

	public BasePanel getBasePanel() {
		return (BasePanel) getJabrefFrame().getTabbedPane().getSelectedComponent();
	}

	public BibtexDatabase getDatabase() {
		if (getBasePanel() == null) {
			return null;
		}
		return getBasePanel().getDatabase();
	}

	public BasePanel addNewDatabase(ParserResult pr, File base, boolean raisePanel) {
		File file = base.getAbsoluteFile();
		String fileName = file.getPath();
		BibtexDatabase database = pr.getDatabase();
		database.addDatabaseChangeListener(ReferencesController.getJabRefChangeListener());
		
		BasePanel bp = new BasePanel(getJabrefFrame(), database, file, pr.getMetaData(), pr.getEncoding());

		// file is set to null inside the EventDispatcherThread
		// SwingUtilities.invokeLater(new OpenItSwingHelper(bp, file,
		// raisePanel));
		
		getJabrefFrame().addTab(bp, file, raisePanel);

		LogUtils.info(Globals.lang("Opened database") + " '" + fileName + "' " + Globals.lang("with") + " "
				+ database.getEntryCount() + " " + Globals.lang("entries") + ".");

		return bp;
	}

	public JabRefBaseHandle openDatabase(File baseFile, boolean raisePanel) {
		JabRefBaseHandle handle = null;
		if(baseFile == null) {
			throw new IllegalArgumentException("NULL");
		}
		File file = baseFile.getAbsoluteFile();
		//closeDatabase(file, true);
		if(isOpened(file)) {
			handle = getBaseHandle(file);
			if(handle != null && raisePanel) {
				getJabrefFrame().showBasePanel(handle.getBasePanel());
			}
		}
		else {
			handle = openIt(file, raisePanel);
			//updateWindowsRegistry(file);
			if(handle != null) {
				synchronized (baseHandles ) {
					baseHandles.put(file, handle);
				}
			}
		}
		//DOCEAR - todo: how do we deal with multiple files?
		DocearController.getController().getDocearEventLogger().appendToLog(this, DocearLogEvent.RM_BIBTEX_FILE_CHANGE, new Object[] {file, this.getDatabase().getEntries().size()});
		return handle;
	}
	
	public JabRefBaseHandle getBaseHandle(File baseFile) {
		if(baseFile == null) {
			throw new IllegalArgumentException("NULL");
		}
		File file = baseFile.getAbsoluteFile();
		synchronized (baseHandles) {
			if(baseHandles.containsKey(file)) {
				return baseHandles.get(file); 
			}
		}
		return null;
	}
	
	public void closeDatabase(File baseFile) {
		File file = baseFile.getAbsoluteFile();
		closeDatabase(file, false);
	}
	
	public void closeDatabase(JabRefBaseHandle baseHandle) {
		if(baseHandle == null) {
			return;
		}
		if(!baseHandle.hasMoreConnections()) {
			synchronized (baseHandles) {	
				baseHandles.remove(baseHandle.getFile().getAbsoluteFile());
			}
			closeDatabase(baseHandle.getFile());
		}		
	}
	
	private void closeDatabase(File file, boolean silentClose) {
		for(int i=0; i < getJabrefFrame().baseCount(); i++) {
			BasePanel panel = getJabrefFrame().baseAt(i);
			if(panel.getFile().equals(file)) {
				getJabrefFrame().showBaseAt(i);
				getJabrefFrame().closeCurrentTab();
				if(!silentClose) {
					//firePanelRemoved(panel, i);
				}
			}
		}
	}
	
	private void closeAll() {
		for(; 0 < getJabrefFrame().baseCount();) {
			getJabrefFrame().closeCurrentTab();
		}
	}
	
	public boolean isOpened(File baseFile) {
		if(baseFile == null) {
			throw new IllegalArgumentException("NULL");
		}
		File file = baseFile.getAbsoluteFile();
		for(int i=0; i < getJabrefFrame().baseCount(); i++) {
			BasePanel panel = getJabrefFrame().baseAt(i);
			if(panel.getFile().equals(file)) {
				return true;			
			}
		}
		return false;
	}

	private void updateWindowsRegistry(File file) {
//		if(Compat.isWindowsOS()) {
//			try {
//				WinRegistry.createKey(WinRegistry.HKEY_CURRENT_USER, "SOFTWARE\\Docear4Word");
//				WinRegistry.writeStringValue(WinRegistry.HKEY_CURRENT_USER, "SOFTWARE\\Docear4Word", "BibTexDatabase", file.getAbsolutePath());
//				WinRegistry.writeStringValue(WinRegistry.HKEY_CURRENT_USER, "ENVIRONMENT", "docear_bibtex_current", file.getAbsolutePath());
//			} 
//			catch (Exception e) {
//				DocearLogger.warn("org.docear.plugin.bibtex.jabref.JabrefWrapper.updateWindowsRegistry(): "+e.getMessage());
//			}
//		}
	}

	private JabRefBaseHandle openIt(File file, boolean raisePanel) {
		JabRefBaseHandle handle = null;
		if ((file != null) && (file.exists())) {
			if (!isCompatibleToJabref(file)) {
				JHyperlink hyperlink = new JHyperlink("http://www.docear.org/support/user-manual/#docear_and_mendeley ",
						"http://www.docear.org/support/user-manual/#docear_and_mendeley");
				JPanel panel = new JPanel(new BorderLayout());
				panel.add(new JLabel(TextUtils.getText("jabref_mendeley_incompatible_1")), BorderLayout.NORTH);
				panel.add(hyperlink, BorderLayout.CENTER);
				panel.add(new JLabel(TextUtils.getText("jabref_mendeley_incompatible_2")), BorderLayout.SOUTH);

				int option = JOptionPane.showConfirmDialog(UITools.getFrame(), panel,

				TextUtils.getText("jabref_mendeley_incompatible_title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (option == JOptionPane.YES_OPTION) {
					return handle;
				}
			}
			File fileToLoad = file;
			LogUtils.info(Globals.lang("Opening References") + ": '" + file.getPath() + "'");

			int tryCounter = 0;
			boolean done = false;
			while (!done && tryCounter++ < MAX_TRY_OPEN) {
				String fileName = file.getPath();
				Globals.prefs.put("workingDirectory", file.getPath());
				// Should this be done _after_ we know it was successfully
				// opened?
				ResourceController resourceController = ResourceController.getResourceController();
				String encoding = resourceController.getProperty("docear_bibtex_encoding", Globals.prefs.get("defaultEncoding"));

				if (Util.hasLockFile(file)) {
					long modTime = Util.getLockFileTimeStamp(file);
					if ((modTime != -1) && (System.currentTimeMillis() - modTime > SaveSession.LOCKFILE_CRITICAL_AGE)) {
						// The lock file is fairly old, so we can offer to
						// "steal" the file:
						int answer = JOptionPane.showConfirmDialog(
								null,
								"<html>" + Globals.lang("Error opening file") + " '" + fileName + "'. "
										+ Globals.lang("File is locked by another JabRef instance.") + "<p>"
										+ Globals.lang("Do you want to override the file lock?"), Globals.lang("File locked"),
								JOptionPane.YES_NO_OPTION);
						if (answer == JOptionPane.YES_OPTION) {
							Util.deleteLockFile(file);
						}
						else
							return handle;
					}
					else if (!Util.waitForFileLock(file, 10)) {
						JOptionPane.showMessageDialog(null, Globals.lang("Error opening file") + " '" + fileName + "'. "
								+ Globals.lang("File is locked by another JabRef instance."), Globals.lang("Error"),
								JOptionPane.ERROR_MESSAGE);
						return handle;
					}

				}
				ParserResult pr;
				try {
					String source = resourceController.getProperty("docear_bibtex_source", "Jabref");
					pr = OpenDatabaseAction.loadDataBase(fileToLoad, encoding, source);
				}
				catch (Exception ex) {
					//__DOCEAR_
					ex.printStackTrace();
					pr = null;
				}
				if ((pr == null) || (pr == ParserResult.INVALID_FORMAT)) {
					LogUtils.warn("ERROR: Could not load file" + file);
					continue;
				}
				else {
					done = true;
					final BasePanel panel = addNewDatabase(pr, file, raisePanel);
					
					panel.markNonUndoableBaseChanged();

					handle = new JabRefBaseHandle(panel, pr);
					
					// After adding the database, go through our list and see if
					// any post open actions needs to be done. For instance,
					// checking
					// if we found new entry types that can be imported, or
					// checking
					// if the database contents should be modified due to new
					// features
					// in this version of JabRef:
					final ParserResult prf = pr;
					SwingUtilities.invokeLater(new Runnable() {
						public void run() {
							performPostOpenActions(panel, prf, true);
						}
					});
				}
			}

		}
		
		DocearController.getController().getDocearEventLogger().appendToLog(this, DocearLogEvent.RM_BIBTEX_FILE_OPEN, new Object[] {file, this.getDatabase().getEntries().size()});
		return handle;
	}

	// JabRef does not use character escaping of "{" and "}"
	// unfortunately all other escapings are not unambiguously or might be set
	// in jabref-preferences too
	public boolean isCompatibleToJabref(File f) {
		int escapeCount = 0;
		int allCount = 0;

		ArrayList<Character> allowedCharsBeforeSlash = new ArrayList<Character>();
		allowedCharsBeforeSlash.add('\"');
		allowedCharsBeforeSlash.add('\'');
		allowedCharsBeforeSlash.add('`');
		allowedCharsBeforeSlash.add('^');
		allowedCharsBeforeSlash.add('~');

		Scanner in = null;
		try {
			in = new Scanner(new FileReader(f));
			while (in.hasNextLine()) {
				String line = in.nextLine();

				String normalized = line.trim().toLowerCase();
				if (Compat.isWindowsOS() && normalized.startsWith("file")) {
					if (normalized.contains("backslash$:")) {
						return false;
					}
				}
				if (normalized.startsWith("journal") || normalized.startsWith("title") || normalized.startsWith("booktitle")) {
					int pos = 0;
					int i = 0;

					String s = normalized.substring(normalized.indexOf("=") + 1).trim();
					while (s.charAt(pos) == '{') {
						pos++;
					}
					while ((i = s.indexOf("{", pos)) >= 0) {
						pos = (i + 1);
						if (allowedCharsBeforeSlash.contains(s.charAt(i - 1))) {
							continue;
						}
						allCount++;

					}

					pos = 0;
					i = 0;
					while ((i = s.indexOf("\\{", pos)) >= 0) {
						escapeCount++;
						pos = (i + 1);
					}
				}
			}
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		finally {
			try {
				in.close();
			}
			catch (Exception e) {
				LogUtils.warn(e);
			}
		}

		// if no escaped and no unescaped char sequence was found in the whole
		// file we assume it to be ok for usage in jabref
		if (allCount / 2 >= escapeCount) {
			return true;
		}
		return false;
	}

	/**
	 * Go through the list of post open actions, and perform those that need to
	 * be performed.
	 * 
	 * @param panel
	 *            The BasePanel where the database is shown.
	 * @param pr
	 *            The result of the bib file parse operation.
	 */
	public static void performPostOpenActions(BasePanel panel, ParserResult pr, boolean mustRaisePanel) {
		for (Iterator<PostOpenAction> iterator = postOpenActions.iterator(); iterator.hasNext();) {
			PostOpenAction action = iterator.next();
			if (action.isActionNecessary(pr)) {
				if (mustRaisePanel)
					panel.frame().getTabbedPane().setSelectedComponent(panel);
				action.performAction(panel, pr);
			}
		}
	}

	public void afterViewChange(Component oldView, Component newView) {
	}

	public void afterViewClose(final Component oldView) {
		oldView.removeMouseListener(mapViewListener);
	}

	public void afterViewCreated(final Component mapView) {
		mapView.addMouseListener(mapViewListener);
	}

	public void beforeViewChange(Component oldView, Component newView) {
	}

	public void shutdown() {
		for (JabRefBaseHandle handle : baseHandles.values()) {
			try {
				BibtexDatabase database = handle.getBasePanel().getDatabase();
				if(database == null) {
					return;
				}
				for (BibtexEntry entry : database.getEntries()) {
					if (entry.getField("docear_add_to_node") != null) {
						entry.setField("docear_add_to_node", null);
					}
				}
				if(ReferencesController.getController().getJabrefWrapper().getBasePanel().isUpdatedExternally()){
					DocearController.getController().addWorkingThreadHandle("ReferenceQuitAction");
					SaveDatabaseAction saveAction = new SaveDatabaseAction(handle.getBasePanel());
					saveAction.runCommand();
					if (saveAction.isCancelled() || !saveAction.isSuccess()) {						
						DocearController.getController().dispatchDocearEvent(new DocearEvent(this, null, DocearEventType.APPLICATION_CLOSING_ABORTED));
					}
					DocearController.getController().removeWorkingThreadHandle("ReferenceQuitAction");
				}
				else{
					handle.getBasePanel().runCommand("save");
				}
			}
			catch (Throwable t) {
				LogUtils.warn(t);
			}
		}		
	}

//	public void addBaseHandleForFile(File baseFile, IJabrefChangeListener listener) {
//		if(listener == null || baseFile == null) {
//			return;
//		}
//		File file = baseFile.getAbsoluteFile();
//		synchronized (baseHandles ) {
//			List<IJabrefChangeListener> list = baseHandles.get(file);
//			if(list == null) {
//				list = new ArrayList<IJabrefChangeListener>();
//				baseHandles.put(file, list);
//			}
//			if(list.contains(listener)) {
//				return;
//			}
//			list.add(listener);
//		}		
//	}
	
//	public void removeBaseHandleForFile(File baseFile, IJabrefChangeListener listener) {
//		if(listener == null || baseFile == null) {
//			return;
//		}
//		File file = baseFile.getAbsoluteFile();
//		synchronized (baseHandles ) {
//			List<IJabrefChangeListener> list = baseHandles.get(file);
//			if(list == null) {
//				return;
//			}
//			list.remove(listener);
//		}	
//	}
}
