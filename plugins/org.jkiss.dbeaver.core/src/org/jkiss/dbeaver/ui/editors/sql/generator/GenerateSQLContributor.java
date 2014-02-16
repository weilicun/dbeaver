/*
 * Copyright (C) 2010-2014 Serge Rieder
 * serge@jkiss.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package org.jkiss.dbeaver.ui.editors.sql.generator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.actions.CompoundContributionItem;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithResult;
import org.jkiss.dbeaver.model.sql.SQLDialect;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.runtime.RuntimeUtils;
import org.jkiss.dbeaver.ui.DBIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetSelection;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class GenerateSQLContributor extends CompoundContributionItem {

    static protected final Log log = LogFactory.getLog(GenerateSQLContributor.class);

    @Override
    protected IContributionItem[] getContributionItems()
    {
        IWorkbenchPart part = DBeaverUI.getActiveWorkbenchWindow().getActivePage().getActivePart();
        IStructuredSelection structuredSelection = GenerateSQLContributor.getSelectionFromPart(part);
        if (structuredSelection == null || structuredSelection.isEmpty()) {
            return new IContributionItem[0];
        }

        List<IContributionItem> menu = new ArrayList<IContributionItem>();
        if (structuredSelection instanceof ResultSetSelection) {
            // Results
            makeResultSetContributions(menu, (ResultSetSelection) structuredSelection);

        } else {
            final DBSTable table =
                (DBSTable) ((DBNDatabaseNode)RuntimeUtils.getObjectAdapter(structuredSelection.getFirstElement(), DBNNode.class)).getObject();
            makeTableContributions(menu, table);
        }
        return menu.toArray(new IContributionItem[menu.size()]);
    }

    private void makeTableContributions(List<IContributionItem> menu, final DBSTable table)
    {
        // Table
        menu.add(makeAction("SELECT ", new TableAnalysisRunner(table) {
            @Override
            public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
            {
                sql.append("SELECT ");
                boolean hasAttr = false;
                for (DBSEntityAttribute attr : getAllAttributes(monitor)) {
                    if (hasAttr) sql.append(", ");
                    sql.append(DBUtils.getObjectFullName(attr));
                    hasAttr = true;
                }
                sql.append("\nFROM ").append(DBUtils.getObjectFullName(table));
                sql.append(";\n");
            }
        }));
        menu.add(makeAction("INSERT ", new TableAnalysisRunner(table) {
            @Override
            public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
            {
                sql.append("INSERT INTO ").append(DBUtils.getObjectFullName(table)).append("\n(");
                boolean hasAttr = false;
                for (DBSEntityAttribute attr : getAllAttributes(monitor)) {
                    if (hasAttr) sql.append(", ");
                    sql.append(DBUtils.getObjectFullName(attr));
                    hasAttr = true;
                }
                sql.append(")\nVALUES(");
                hasAttr = false;
                for (DBSEntityAttribute attr : getAllAttributes(monitor)) {
                    if (hasAttr) sql.append(", ");
                    appendDefaultValue(sql, attr);
                    hasAttr = true;
                }
                sql.append(");\n");
            }

        }));
        menu.add(makeAction("UPDATE ", new TableAnalysisRunner(table) {
            @Override
            public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
            {
                Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor);
                sql.append("UPDATE ").append(DBUtils.getObjectFullName(table))
                    .append("\nSET ");
                boolean hasAttr = false;
                for (DBSEntityAttribute attr : getValueAttributes(monitor, keyAttributes)) {
                    if (hasAttr) sql.append(", ");
                    sql.append(DBUtils.getObjectFullName(attr)).append("=");
                    appendDefaultValue(sql, attr);
                    hasAttr = true;
                }
                if (!CommonUtils.isEmpty(keyAttributes)) {
                    sql.append("\nWHERE ");
                    hasAttr = false;
                    for (DBSEntityAttribute attr : keyAttributes) {
                        if (hasAttr) sql.append(" AND ");
                        sql.append(DBUtils.getObjectFullName(attr)).append("=");
                        appendDefaultValue(sql, attr);
                        hasAttr = true;
                    }
                }
                sql.append(";\n");
            }
        }));
        menu.add(makeAction("DELETE ", new TableAnalysisRunner(table) {
            @Override
            public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
            {
                sql.append("DELETE FROM  ").append(DBUtils.getObjectFullName(table))
                    .append("\nWHERE ");
                Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor);
                if (CommonUtils.isEmpty(keyAttributes)) {
                    keyAttributes = getAllAttributes(monitor);
                }
                boolean hasAttr = false;
                for (DBSEntityAttribute attr : keyAttributes) {
                    if (hasAttr) sql.append(" AND ");
                    sql.append(DBUtils.getObjectFullName(attr)).append("=");
                    appendDefaultValue(sql, attr);
                    hasAttr = true;
                }
                sql.append(";\n");
            }
        }));
        menu.add(makeAction("MERGE", new TableAnalysisRunner(table) {
            @Override
            public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
            {
                boolean hasAttr = false;

                sql.append("MERGE INTO ").append(DBUtils.getObjectFullName(table)).append(" AS tgt\n");
                sql.append("USING SOURCE_TABLE AS src\n");
                Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor);
                if (!CommonUtils.isEmpty(keyAttributes)) {
                    sql.append("ON (");
                    for (DBSEntityAttribute attr : keyAttributes) {
                        if (hasAttr) sql.append(" AND ");
                        sql.append("tgt.").append(DBUtils.getQuotedIdentifier(attr))
                            .append("=src.").append(DBUtils.getQuotedIdentifier(attr));
                        hasAttr = true;
                    }
                    sql.append(")\n");
                }
                sql.append("WHEN MATCHED\nTHEN UPDATE SET\n");
                hasAttr = false;
                for (DBSEntityAttribute attr : getValueAttributes(monitor, keyAttributes)) {
                    if (hasAttr) sql.append(", ");
                    sql.append("tgt.").append(DBUtils.getQuotedIdentifier(attr))
                        .append("=src.").append(DBUtils.getQuotedIdentifier(attr));
                    hasAttr = true;
                }
                sql.append("\nWHEN NOT MATCHED\nTHEN INSERT (");
                hasAttr = false;
                for (DBSEntityAttribute attr : getAllAttributes(monitor)) {
                    if (hasAttr) sql.append(", ");
                    sql.append(DBUtils.getQuotedIdentifier(attr));
                    hasAttr = true;
                }
                sql.append(")\nVALUES (");
                hasAttr = false;
                for (DBSEntityAttribute attr : getAllAttributes(monitor)) {
                    if (hasAttr) sql.append(", ");
                    sql.append("src.").append(DBUtils.getQuotedIdentifier(attr));
                    hasAttr = true;
                }
                sql.append(");\n");
            }
        }));
    }

    private void makeResultSetContributions(List<IContributionItem> menu, ResultSetSelection rss)
    {
        final ResultSetViewer rsv = rss.getResultSetViewer();
        DBSEntity entity = rsv.getDataContainer() instanceof DBSEntity ? (DBSEntity) rsv.getDataContainer() : null;
        if (entity == null) {
            entity = rsv.getModel().getSingleSource();
        }
        if (entity != null) {
            final Collection<ResultSetRow> selectedRows = rss.getSelectedRows();
            if (!CommonUtils.isEmpty(selectedRows)) {
                menu.add(makeAction("SELECT by Unique Key", new TableAnalysisRunner(entity) {
                    @Override
                    public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
                    {
                        for (ResultSetRow firstRow : selectedRows) {

                            Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor);
                            sql.append("SELECT ");
                            boolean hasAttr = false;
                            for (DBSEntityAttribute attr : getValueAttributes(monitor, keyAttributes)) {
                                if (hasAttr) sql.append(", ");
                                sql.append(DBUtils.getObjectFullName(attr));
                                hasAttr = true;
                            }
                            sql.append("\nFROM ").append(DBUtils.getObjectFullName(table));
                            sql.append("\nWHERE ");
                            hasAttr = false;
                            for (DBSEntityAttribute attr : keyAttributes) {
                                if (hasAttr) sql.append(" AND ");
                                DBDAttributeBinding binding = rsv.getModel().getAttributeBinding(attr);
                                sql.append(DBUtils.getObjectFullName(attr)).append("=");
                                if (binding == null) {
                                    appendDefaultValue(sql, attr);
                                } else {
                                    appendAttributeValue(sql, binding, firstRow);
                                }
                                hasAttr = true;
                            }
                            sql.append(";\n");
                        }
                    }
                }));
                menu.add(makeAction("INSERT", new TableAnalysisRunner(entity) {
                    @Override
                    public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
                    {
                        for (ResultSetRow firstRow : selectedRows) {

                            Collection<? extends DBSEntityAttribute> allAttributes = getAllAttributes(monitor);
                            sql.append("INSERT INTO ").append(DBUtils.getObjectFullName(table));
                            sql.append("\n(");
                            boolean hasAttr = false;
                            for (DBSEntityAttribute attr : allAttributes) {
                                if (hasAttr) sql.append(", ");
                                sql.append(DBUtils.getObjectFullName(attr));
                                hasAttr = true;
                            }
                            sql.append(")\nVALUES(");
                            hasAttr = false;
                            for (DBSEntityAttribute attr : allAttributes) {
                                if (hasAttr) sql.append(", ");
                                DBDAttributeBinding binding = rsv.getModel().getAttributeBinding(attr);
                                if (binding == null) {
                                    appendDefaultValue(sql, attr);
                                } else {
                                    appendAttributeValue(sql, binding, firstRow);
                                }
                                hasAttr = true;
                            }
                            sql.append(");\n");
                        }
                    }
                }));

                menu.add(makeAction("DELETE by Unique Key", new TableAnalysisRunner(entity) {
                    @Override
                    public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql) throws DBException
                    {
                        for (ResultSetRow firstRow : selectedRows) {

                            Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor);
                            sql.append("DELETE FROM ").append(DBUtils.getObjectFullName(table));
                            sql.append("\nWHERE ");
                            boolean hasAttr = false;
                            for (DBSEntityAttribute attr : keyAttributes) {
                                if (hasAttr) sql.append(" AND ");
                                DBDAttributeBinding binding = rsv.getModel().getAttributeBinding(attr);
                                sql.append(DBUtils.getObjectFullName(attr)).append("=");
                                if (binding == null) {
                                    appendDefaultValue(sql, attr);
                                } else {
                                    appendAttributeValue(sql, binding, firstRow);
                                }
                                hasAttr = true;
                            }
                            sql.append(";\n");
                        }
                    }
                }));
            }
        }
    }

    private abstract static class TableAnalysisRunner extends DBRRunnableWithResult<String> {
        final DBSEntity table;

        protected TableAnalysisRunner(DBSEntity table)
        {
            this.table = table;
        }

        @Override
        public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
        {
            StringBuilder sql = new StringBuilder(100);
            try {
                generateSQL(monitor, sql);
            } catch (DBException e) {
                throw new InvocationTargetException(e);
            }
            result = sql.toString();
        }

        protected abstract void generateSQL(DBRProgressMonitor monitor, StringBuilder sql)
            throws DBException;

        protected Collection<? extends DBSEntityAttribute> getAllAttributes(DBRProgressMonitor monitor) throws DBException
        {
            return CommonUtils.safeCollection(table.getAttributes(monitor));
        }

        protected Collection<? extends DBSEntityAttribute> getKeyAttributes(DBRProgressMonitor monitor) throws DBException
        {
            return DBUtils.getBestTableIdentifier(monitor, table);
        }

        protected Collection<? extends DBSEntityAttribute> getValueAttributes(DBRProgressMonitor monitor, Collection<? extends DBSEntityAttribute> keyAttributes) throws DBException
        {
            if (CommonUtils.isEmpty(keyAttributes)) {
                return getAllAttributes(monitor);
            }
            List<DBSEntityAttribute> valueAttributes = new ArrayList<DBSEntityAttribute>(getAllAttributes(monitor));
            for (Iterator<DBSEntityAttribute> iter = valueAttributes.iterator(); iter.hasNext(); ) {
                if (keyAttributes.contains(iter.next())) {
                    iter.remove();
                }
            }
            return valueAttributes;
        }

        protected void appendDefaultValue(StringBuilder sql, DBSEntityAttribute attr)
        {
            if (!CommonUtils.isEmpty(attr.getDefaultValue())) {
                sql.append(attr.getDefaultValue());
            }
            switch (attr.getDataKind()) {
                case BOOLEAN: sql.append("false"); break;
                case NUMERIC: sql.append("0"); break;
                case STRING:
                case DATETIME:
                    sql.append("''"); break;
                default: sql.append("?"); break;
            }
        }

        protected void appendAttributeValue(StringBuilder sql, DBDAttributeBinding binding, ResultSetRow row)
        {
            SQLDialect dialect = SQLUtils.getDialectFromObject(binding.getAttribute());
            Object value = row.getValue(binding.getAttribute());
            if (DBUtils.isNullValue(value)) {
                sql.append("NULL");
            } else {
                boolean isString = binding.getAttribute().getDataKind() == DBPDataKind.STRING;
                String displayString = binding.getValueHandler().getValueDisplayString(
                    binding.getAttribute(),
                    value,
                    DBDDisplayFormat.NATIVE);
                if (isString) sql.append('\'');
                if (dialect != null) {
                    sql.append(dialect.escapeString(displayString));
                } else {
                    sql.append(displayString);
                }
                if (isString) sql.append('\'');
            }
        }
    }
    
    private static ContributionItem makeAction(String text, final DBRRunnableWithResult<String> runnable)
    {
        return new ActionContributionItem(
            new Action(text, DBIcon.SQL_TEXT.getImageDescriptor()) {
                @Override
                public void run()
                {
                    DBeaverUI.runInUI(DBeaverUI.getActiveWorkbenchWindow(), runnable);
                    String sql = runnable.getResult();
                    IEditorPart activeEditor = DBeaverUI.getActiveWorkbenchWindow().getActivePage().getActiveEditor();
                    if (activeEditor instanceof AbstractTextEditor) {
                        AbstractTextEditor textEditor = (AbstractTextEditor)activeEditor;
                        ITextSelection selection = (ITextSelection) textEditor.getSelectionProvider().getSelection();
                        IDocumentProvider provider=textEditor.getDocumentProvider();
                        IDocument doc = provider.getDocument(activeEditor.getEditorInput());
                        try {
                            doc.replace(selection.getOffset(), selection.getLength(), sql);
                        } catch (BadLocationException e) {
                            log.warn(e);
                        }
                        activeEditor.setFocus();
                    }
                    UIUtils.setClipboardContents(DBeaverUI.getActiveWorkbenchShell().getDisplay(), TextTransfer.getInstance(), sql);
                }
        });
    }

    @Nullable
    static IStructuredSelection getSelectionFromPart(IWorkbenchPart part)
    {
        if (part == null) {
            return null;
        }
        ISelectionProvider selectionProvider = part.getSite().getSelectionProvider();
        if (selectionProvider == null) {
            return null;
        }
        ISelection selection = selectionProvider.getSelection();
        if (selection.isEmpty() || !(selection instanceof IStructuredSelection)) {
            return null;
        }
        return (IStructuredSelection)selection;
    }

}
