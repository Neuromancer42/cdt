/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Markus Schorn - initial API and implementation
 *******************************************************************************/ 
package org.eclipse.cdt.internal.ui.actions;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartSite;

import org.eclipse.cdt.core.dom.ast.ASTVisitor;
import org.eclipse.cdt.core.dom.ast.IASTName;
import org.eclipse.cdt.core.dom.ast.IASTPreprocessorIncludeStatement;
import org.eclipse.cdt.core.dom.ast.IASTPreprocessorMacroDefinition;
import org.eclipse.cdt.core.dom.ast.IASTProblem;
import org.eclipse.cdt.core.dom.ast.IASTTranslationUnit;
import org.eclipse.cdt.core.dom.ast.IBinding;
import org.eclipse.cdt.core.dom.ast.IProblemBinding;
import org.eclipse.cdt.core.dom.ast.cpp.ICPPASTQualifiedName;
import org.eclipse.cdt.core.index.IIndex;
import org.eclipse.cdt.core.index.IIndexFile;
import org.eclipse.cdt.core.index.IIndexFileLocation;
import org.eclipse.cdt.core.index.IIndexInclude;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.cdt.core.parser.ExtendedScannerInfo;

import org.eclipse.cdt.internal.core.model.ASTCache;
import org.eclipse.cdt.internal.core.model.TranslationUnit;
import org.eclipse.cdt.internal.core.pdom.ASTFilePathResolver;
import org.eclipse.cdt.internal.core.pdom.PDOM;
import org.eclipse.cdt.internal.core.pdom.indexer.ProjectIndexerInputAdapter;

import org.eclipse.cdt.internal.ui.editor.ASTProvider;

public class CreateParserLogAction implements IObjectActionDelegate {

	private static final class MyVisitor extends ASTVisitor {
		List<IASTProblem> fProblems= new ArrayList<IASTProblem>();
		List<IProblemBinding> fProblemBindings= new ArrayList<IProblemBinding>();
		List<Exception> fExceptions= new ArrayList<Exception>();

		MyVisitor() {
			shouldVisitProblems= true;
			shouldVisitNames= true;
		}

		@Override
		public int visit(IASTProblem problem) {
			fProblems.add(problem);
			return PROCESS_SKIP;
		}
		
		@Override
		public int visit(IASTName name) {
			if (name instanceof ICPPASTQualifiedName) {
				return PROCESS_CONTINUE;
			}
			try {
				IBinding binding= name.resolveBinding();
				if (binding instanceof IProblemBinding) {
					fProblemBindings.add((IProblemBinding) binding);
				}
			} catch (RuntimeException e) {
				fExceptions.add(e);
			}
			return PROCESS_CONTINUE;
		}
	}

	private ISelection fSelection;
	private IWorkbenchPartSite fSite;
	
	public void setActivePart(IAction action, IWorkbenchPart targetPart) {
		fSite= targetPart.getSite();
	}

	public void selectionChanged(IAction action, ISelection selection) {
		fSelection = selection;
	}

	public void run(IAction action) {
		if (!(fSelection instanceof IStructuredSelection))
			return;
		
		final String title= action.getText().replace("&", ""); //$NON-NLS-1$ //$NON-NLS-2$
		IStructuredSelection cElements= SelectionConverter.convertSelectionToCElements(fSelection);
		Iterator i= cElements.iterator();
		ArrayList<ITranslationUnit> tuSelection= new ArrayList<ITranslationUnit>();
		while (i.hasNext()) {
			Object o= i.next();
			if (o instanceof ITranslationUnit) {
				tuSelection.add((ITranslationUnit) o);
			}
		}
		ITranslationUnit[] tuArray= tuSelection.toArray(new ITranslationUnit[tuSelection.size()]);
		if (tuArray.length == 0) {
			return;
		}
		FileDialog dlg= new FileDialog(fSite.getShell(), SWT.SAVE);
		dlg.setText(title);
		dlg.setFilterExtensions(new String[]{"*.log"});  //$NON-NLS-1$
		String path= null;
		while(path == null) {
			path= dlg.open();
			if (path == null)
				return;

			File file= new File(path);		
			if (file.exists()) {
				if (!file.canWrite()) {
					final String msg= NLS.bind(ActionMessages.getString("CreateParserLogAction.readOnlyFile"), path); //$NON-NLS-1$
					MessageDialog.openError(fSite.getShell(), title, msg);
					path= null;
				}
				else {
					final String msg = NLS.bind(ActionMessages.getString("CreateParserLogAction.existingFile"), path); //$NON-NLS-1$
					if (!MessageDialog.openQuestion(fSite.getShell(), title, msg)) {
						path= null;
					}
				}
			}
		}
		
		try {
			PrintStream out= new PrintStream(path);
			try {
				boolean needsep= false;
				for (ITranslationUnit tu : tuArray) {
					if (needsep) {
						out.println(); out.println(); 
					}
					createLog(out, tu, new NullProgressMonitor());
					needsep= true;
				}
			}
			finally {
				out.close();
			}
		} catch (IOException e) {
			MessageDialog.openError(fSite.getShell(), action.getText(), e.getMessage());
		}
	}

	private void createLog(final PrintStream out, final ITranslationUnit tu, IProgressMonitor pm) {
		ASTProvider.getASTProvider().runOnAST(tu, ASTProvider.WAIT_YES, pm, new ASTCache.ASTRunnable() {
			public IStatus runOnAST(ILanguage lang, IASTTranslationUnit ast) throws CoreException {
				return createLog(out, tu, lang, ast);
			}
		});
	}

	protected IStatus createLog(PrintStream out, ITranslationUnit tu, ILanguage lang, IASTTranslationUnit ast) {
		IStatus status = Status.OK_STATUS;
		final ICProject cproject = tu.getCProject();
		final String projectName= cproject == null ? null : cproject.getElementName();
		
		ITranslationUnit ctx= tu;
		if (tu instanceof TranslationUnit) {
			TranslationUnit itu= (TranslationUnit) tu;
			ctx= itu.getSourceContextTU(ast.getIndex(), ITranslationUnit.AST_CONFIGURE_USING_SOURCE_CONTEXT);
		}
		final ExtendedScannerInfo scfg= new ExtendedScannerInfo(ctx.getScannerInfo(true));
		final String indent= "   "; //$NON-NLS-1$
		final MyVisitor visitor= new MyVisitor();
		ast.accept(visitor);
		
		out.println("Project:       " + projectName); //$NON-NLS-1$
		out.println("Index Version: " + PDOM.CURRENT_VERSION); //$NON-NLS-1$
		out.println("File:          " + tu.getLocationURI()); //$NON-NLS-1$
		out.println("Context:       " + ctx.getLocationURI()); //$NON-NLS-1$
		out.println("Language:      " + lang.getName()); //$NON-NLS-1$
		out.println();
		out.println("Include Search Path (option -I):");  //$NON-NLS-1$
		output(out, indent, scfg.getIncludePaths());
		out.println();
		out.println("Local Include Search Path (option -iquote):"); //$NON-NLS-1$
		output(out, indent, scfg.getLocalIncludePath());
		out.println();
		out.println("Preincluded files (option -include):"); //$NON-NLS-1$
		output(out, indent, scfg.getIncludeFiles());
		out.println();
		out.println("Preincluded macro files (option -imacros):"); //$NON-NLS-1$
		output(out, indent, scfg.getMacroFiles());
		out.println();
		out.println("Macro definitions (option -D):"); //$NON-NLS-1$
		HashSet<String> reported= new HashSet<String>();
		output(out, indent, scfg.getDefinedSymbols(), reported);
		out.println();
		out.println("Macro definitions (from configuration + headers in index):"); //$NON-NLS-1$
		output(out, indent, ast.getBuiltinMacroDefinitions(), reported);
		out.println();
		out.println("Macro definitions (from files actually parsed):"); //$NON-NLS-1$
		output(out, indent, ast.getMacroDefinitions(), reported);

		out.println();
		out.println("Unresolved includes (from headers in index):"); //$NON-NLS-1$
		try {
			outputUnresolvedIncludes(cproject, ast.getIndex(), out, indent, ast.getIncludeDirectives(), ast.getLinkage().getLinkageID());
		} catch (CoreException e) {
			status= e.getStatus();
		}
		
		out.println();
		out.println("Scanner problems:"); //$NON-NLS-1$
		output(out, indent, ast.getPreprocessorProblems());

		out.println();
		out.println("Parser problems:"); //$NON-NLS-1$
		output(out, indent, visitor.fProblems.toArray(new IASTProblem[visitor.fProblems.size()]));
		
		out.println();
		out.println("Unresolved names:"); //$NON-NLS-1$
		output(out, indent, visitor.fProblemBindings);

		out.println();
		out.println("Exceptions in name resolution:"); //$NON-NLS-1$
		output(out, visitor.fExceptions);

		return status;
	}

	private void outputUnresolvedIncludes(ICProject prj, IIndex index, PrintStream out, String indent, 
			IASTPreprocessorIncludeStatement[] includeDirectives, int linkageID) throws CoreException {
		ASTFilePathResolver resolver= new ProjectIndexerInputAdapter(prj);
		for (IASTPreprocessorIncludeStatement include : includeDirectives) {
			if (include.isActive() && include.isResolved()) {
				outputUnresolvedIncludes(index, out, indent, resolver.resolveASTPath(include.getPath()), linkageID);
			}
		}
	}

	private void outputUnresolvedIncludes(IIndex index, PrintStream out, String indent, 
			IIndexFileLocation ifl, int linkageID) throws CoreException {
		IIndexFile ifile= index.getFile(linkageID, ifl);
		if (ifile == null) {
			out.println(indent + ifl.getURI() + " is not indexed"); //$NON-NLS-1$
		}
		else {
			IIndexInclude[] includes = ifile.getIncludes();
			for (IIndexInclude inc : includes) {
				if (inc.isActive()) {
					if (inc.isResolved()) {
						outputUnresolvedIncludes(index, out, indent, inc.getIncludesLocation(), linkageID);
					}
					else {
						out.println(indent + "Unresolved inclusion: " + inc.getName() + " in file " +  //$NON-NLS-1$//$NON-NLS-2$
								inc.getIncludedByLocation().getURI());
					}
				}
			}
		}
	}

	private void output(PrintStream out, String indent, String[] list) {
		for (String line : list) {
			out.println(indent + line);
		}
	}

	private void output(PrintStream out, String indent, Map<String, String> definedSymbols, HashSet<String> reported) {
		for (Entry<String, String> entry : definedSymbols.entrySet()) {
			final String macro = entry.getKey() + '=' + entry.getValue();
			if (reported.add(macro)) {
				out.println(indent + macro);
			}
		}
	}
	
	private void output(PrintStream out, String indent,	IASTPreprocessorMacroDefinition[] defs, HashSet<String> reported) {
		for (IASTPreprocessorMacroDefinition def : defs) {
			String macro= def.toString();
			if (reported.add(macro)) {
				out.println(indent + macro);
			}
		}
	}
	
	private void output(PrintStream out, String indent,	IASTProblem[] preprocessorProblems) {
		for (IASTProblem problem : preprocessorProblems) {
			out.println(indent + problem.getMessage());
		}
	}
	
	private void output(PrintStream out, String indent, List<IProblemBinding> list) {
		for (IProblemBinding problem : list) {
	        String file= problem.getFileName();
	        int line = problem.getLineNumber();
			out.println(indent + problem.getMessage() + " in file " + file + ':' + line); //$NON-NLS-1$
		}
	}

	private void output(PrintStream out, List<Exception> list) {
		for (Exception problem : list) {
			problem.printStackTrace(out);
		}
	}
}
