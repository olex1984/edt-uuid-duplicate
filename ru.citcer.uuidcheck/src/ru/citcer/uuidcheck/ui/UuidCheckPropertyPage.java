/*-----------------------------------------------------------------------------
 * Copyright (c) 2026 Oleg Zitzer.
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package ru.citcer.uuidcheck.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;

import ru.citcer.uuidcheck.Messages;
import ru.citcer.uuidcheck.UuidCheckPlugin;
import ru.citcer.uuidcheck.core.IUuidIndexService;
import ru.citcer.uuidcheck.core.UuidPreferences;

/** Настройки уровня проекта (ProjectScope). */
public final class UuidCheckPropertyPage extends PropertyPage {

    private static final String[] MODES = {"IGNORE", "WARN", "ERROR"};

    private final UuidPreferences preferences = new UuidPreferences();
    private IProject project;
    private Button enabledButton;
    private Text extensionsText;
    private Combo intraCombo;

    @Override
    protected Control createContents(Composite parent) {
        Object element = getElement();
        project = element instanceof IAdaptable adaptable ? adaptable.getAdapter(IProject.class) : null;

        Composite composite = new Composite(parent, SWT.NONE);
        composite.setLayout(new GridLayout(1, false));
        composite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        if (project == null || !project.exists()) {
            new Label(composite, SWT.NONE).setText(Messages.ErrorNoProject);
            return composite;
        }

        enabledButton = new Button(composite, SWT.CHECK);
        enabledButton.setText(Messages.PropertyEnabled);
        enabledButton.setSelection(preferences.enabled(project));

        new Label(composite, SWT.NONE).setText(Messages.PropertyExtensions);
        extensionsText = new Text(composite, SWT.BORDER);
        extensionsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        extensionsText.setText(String.join(",", preferences.extensions(project)));

        new Label(composite, SWT.NONE).setText(Messages.PropertyIntraMode);
        intraCombo = new Combo(composite, SWT.READ_ONLY);
        intraCombo.setItems(new String[] {Messages.IntraIgnore, Messages.IntraWarn, Messages.IntraError});
        intraCombo.select(indexOfMode(preferences.intraFileMode(project).name()));
        return composite;
    }

    private static int indexOfMode(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equals(mode)) {
                return i;
            }
        }
        return 1;
    }

    @Override
    public boolean performOk() {
        if (project == null || !project.exists()) {
            return true;
        }
        IEclipsePreferences node = new ProjectScope(project).getNode(UuidCheckPlugin.PLUGIN_ID);
        node.put(UuidPreferences.SCAN_ENABLED, Boolean.toString(enabledButton.getSelection()));
        node.put(UuidPreferences.SCAN_EXTENSIONS, extensionsText.getText().trim());
        node.put(UuidPreferences.INTRA_FILE_MODE,
                MODES[Math.max(0, Math.min(MODES.length - 1, intraCombo.getSelectionIndex()))]);
        try {
            node.flush();
        } catch (org.osgi.service.prefs.BackingStoreException e) {
            UuidCheckPlugin.logError("Не удалось сохранить настройки проекта", e);
        }
        IUuidIndexService service = UuidCheckPlugin.getIndexService();
        if (service != null) {
            service.requestScan(project, false);
        }
        return true;
    }
}
