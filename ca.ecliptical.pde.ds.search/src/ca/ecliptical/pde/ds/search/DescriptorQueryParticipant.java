/*******************************************************************************
 * Copyright (c) 2015 Ecliptical Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ecliptical Software Inc. - initial API and implementation
 *******************************************************************************/
package ca.ecliptical.pde.ds.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.ui.search.ElementQuerySpecification;
import org.eclipse.jdt.ui.search.IMatchPresentation;
import org.eclipse.jdt.ui.search.IQueryParticipant;
import org.eclipse.jdt.ui.search.ISearchRequestor;
import org.eclipse.jdt.ui.search.PatternQuerySpecification;
import org.eclipse.jdt.ui.search.QuerySpecification;
import org.eclipse.jface.text.Document;
import org.eclipse.pde.core.IBaseModel;
import org.eclipse.pde.core.plugin.IPluginModelBase;
import org.eclipse.pde.core.plugin.PluginRegistry;
import org.eclipse.pde.internal.core.bundle.WorkspaceBundleFragmentModel;
import org.eclipse.pde.internal.core.bundle.WorkspaceBundlePluginModel;
import org.eclipse.pde.internal.core.bundle.WorkspaceBundlePluginModelBase;
import org.eclipse.pde.internal.core.ibundle.IBundleModel;
import org.eclipse.pde.internal.core.ibundle.IBundlePluginModelBase;
import org.eclipse.pde.internal.core.project.PDEProject;
import org.eclipse.pde.internal.core.text.IDocumentAttributeNode;
import org.eclipse.pde.internal.core.text.IDocumentElementNode;
import org.eclipse.pde.internal.ds.core.IDSComponent;
import org.eclipse.pde.internal.ds.core.IDSConstants;
import org.eclipse.pde.internal.ds.core.IDSImplementation;
import org.eclipse.pde.internal.ds.core.IDSProvide;
import org.eclipse.pde.internal.ds.core.IDSReference;
import org.eclipse.pde.internal.ds.core.IDSService;
import org.eclipse.pde.internal.ds.core.text.DSModel;
import org.eclipse.pde.internal.ui.util.ModelModification;
import org.eclipse.pde.internal.ui.util.PDEModelUtility;
import org.eclipse.search.ui.text.Match;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

@SuppressWarnings("restriction")
public class DescriptorQueryParticipant implements IQueryParticipant {

	private static final Debug debug = Debug.getDebug("ds-query-participant"); //$NON-NLS-1$

	private static final String SERVICE_COMPONENT_HEADER = "Service-Component"; //$NON-NLS-1$

	private static final String VOID_SIG = Signature.createTypeSignature("void", true); //$NON-NLS-1$

	private static final String COMPONENT_CONTEXT_SIG = Signature.createTypeSignature("org.osgi.service.component.ComponentContext", true); //$NON-NLS-1$

	private static final String BUNDLE_CONTEXT_SIG = Signature.createTypeSignature("org.osgi.framework.BundleContext", true); //$NON-NLS-1$

	private static final String MAP_SIG = Signature.createTypeSignature("java.util.Map", true); //$NON-NLS-1$

	private static final String INT_SIG = Signature.createTypeSignature("int", true); //$NON-NLS-1$

	private static final String INTEGER_SIG = Signature.createTypeSignature("java.lang.Integer", true); //$NON-NLS-1$

	private static final String SERVICE_REFERENCE_SIG = Signature.createTypeSignature("org.osgi.framework.ServiceReference", true); //$NON-NLS-1$

	private ISearchRequestor requestor;

	private IJavaElement searchElement;

	private SearchPatternDescriptor searchPattern;

	private int searchFor = -1;

	public void search(ISearchRequestor requestor, QuerySpecification query, IProgressMonitor monitor) throws CoreException {
		if (debug.isDebugging())
			debug.trace(String.format("Query: %s", query)); //$NON-NLS-1$

		// we only look for straight references
		switch (query.getLimitTo()) {
		case IJavaSearchConstants.REFERENCES:
		case IJavaSearchConstants.ALL_OCCURRENCES:
			break;
		default:
			return;
		}

		// we only look for types and methods
		if (query instanceof ElementQuerySpecification) {
			searchElement = ((ElementQuerySpecification) query).getElement();
			switch (searchElement.getElementType()) {
			case IJavaElement.TYPE:
			case IJavaElement.METHOD:
				break;
			default:
				return;
			}
		} else {
			String pattern = ((PatternQuerySpecification) query).getPattern();
			boolean ignoreMethodParams = false;
			searchFor = ((PatternQuerySpecification) query).getSearchFor();
			switch (searchFor) {
			case IJavaSearchConstants.UNKNOWN:
			case IJavaSearchConstants.METHOD:
				int leftParen = pattern.lastIndexOf('(');
				int rightParen = pattern.indexOf(')');
				ignoreMethodParams = leftParen == -1 || rightParen == -1 || leftParen >= rightParen;
				// no break
			case IJavaSearchConstants.TYPE:
			case IJavaSearchConstants.CLASS:
			case IJavaSearchConstants.CLASS_AND_INTERFACE:
			case IJavaSearchConstants.CLASS_AND_ENUM:
			case IJavaSearchConstants.INTERFACE:
			case IJavaSearchConstants.INTERFACE_AND_ANNOTATION:
				break;
			default:
				return;
			}

			// searchPattern = PatternConstructor.createPattern(pattern, ((PatternQuerySpecification) query).isCaseSensitive());
			int matchMode = getMatchMode(pattern) | SearchPattern.R_ERASURE_MATCH;
			if (((PatternQuerySpecification) query).isCaseSensitive())
				matchMode |= SearchPattern.R_CASE_SENSITIVE;

			searchPattern = new SearchPatternDescriptor(pattern, matchMode, ignoreMethodParams);
		}

		HashSet<IPath> scope = new HashSet<IPath>();
		for (IPath path : query.getScope().enclosingProjectsAndJars()) {
			if (path.isAbsolute() && path.segmentCount() == 1)
				scope.add(path);
		}

		if (scope.isEmpty())
			return;

		this.requestor = requestor;

		// look through all active bundles
		IPluginModelBase[] models = PluginRegistry.getWorkspaceModels();
		monitor.beginTask(Messages.DescriptorQueryParticipant_taskName, models.length);
		try {
			for (IPluginModelBase model : models) {
				if (monitor.isCanceled())
					throw new OperationCanceledException();

				if (!model.isEnabled() || !(model instanceof IBundlePluginModelBase)) {
					monitor.worked(1);
					if (debug.isDebugging())
						debug.trace(String.format("Non-bundle model: %s", model)); //$NON-NLS-1$

					continue;
				}

				IProject project = model.getUnderlyingResource().getProject();
				if (!scope.contains(project.getFullPath())) {
					monitor.worked(1);
					if (debug.isDebugging())
						debug.trace(String.format("Project out of scope: %s", project.getName())); //$NON-NLS-1$

					continue;
				}

				// we can only search in Java bundle projects (for now)
				if (!project.hasNature(JavaCore.NATURE_ID) ) {
					monitor.worked(1);
					if (debug.isDebugging())
						debug.trace(String.format("Non-Java project: %s", project.getName())); //$NON-NLS-1$

					continue;
				}

				PDEModelUtility.modifyModel(new ModelModification(project) {
					@Override
					protected void modifyModel(IBaseModel model, IProgressMonitor monitor) throws CoreException {
						if (model instanceof IBundlePluginModelBase)
							searchBundle((IBundlePluginModelBase) model, monitor);
					}
				}, new SubProgressMonitor(monitor, 1));
			}
		} finally {
			monitor.done();
		}
	}

	private int getMatchMode(String pattern) {
		if (pattern.indexOf('*') != -1 || pattern.indexOf('?') != -1)
			return SearchPattern.R_PATTERN_MATCH;

		if (SearchPattern.validateMatchRule(pattern, SearchPattern.R_CAMELCASE_MATCH) == SearchPattern.R_CAMELCASE_MATCH)
			return SearchPattern.R_CAMELCASE_MATCH;

		return SearchPattern.R_EXACT_MATCH;
	}

	private void searchBundle(IBundlePluginModelBase model, IProgressMonitor monitor) throws CoreException {
		IBundleModel bundleModel = model.getBundleModel();
		if (bundleModel == null) {
			if (debug.isDebugging())
				debug.trace(String.format("No bundle model found: %s", model)); //$NON-NLS-1$

			return;
		}

		IProject project = model.getUnderlyingResource().getProject();

		WorkspaceBundlePluginModelBase workspaceModel;
		if (bundleModel.isFragmentModel())
			workspaceModel = new WorkspaceBundleFragmentModel(PDEProject.getManifest(project), null);
		else
			workspaceModel = new WorkspaceBundlePluginModel(PDEProject.getManifest(project), null);

		workspaceModel.load();

		String header = workspaceModel.getBundleModel().getBundle().getHeader(SERVICE_COMPONENT_HEADER);
		if (header == null) {
			if (debug.isDebugging())
				debug.trace(String.format("No Service-Component header in bundle: %s", workspaceModel)); //$NON-NLS-1$

			return;
		}

		String[] elements = header.split("\\s*,\\s*"); //$NON-NLS-1$
		for (String element : elements) {
			if (element.isEmpty())
				continue;

			IPath path = new Path(element);
			String lastSegment = path.lastSegment();
			final HashSet<IFile> files = new HashSet<IFile>();
			if (lastSegment.indexOf('*') >= 0) {
				// wildcard path; get all entries in directory
				final IPath folderPath = path.removeLastSegments(1);
				IFolder folder = PDEProject.getBundleRelativeFolder(project, folderPath);
				if (!folder.exists()) {
					if (debug.isDebugging())
						debug.trace(String.format("Descriptor folder does not exist: %s", folder.getFullPath())); //$NON-NLS-1$

					continue;
				}

				final Filter filter;
				try {
					filter = FrameworkUtil.createFilter("(filename=" + sanitizeFilterValue(element) + ")"); //$NON-NLS-1$ //$NON-NLS-2$
				} catch (InvalidSyntaxException e) {
					// ignore
					continue;
				}

				folder.accept(new IResourceVisitor() {
					public boolean visit(IResource resource) throws CoreException {
						if (resource.getType() == IResource.FILE) {
							String pathStr = folderPath.append(resource.getName()).toString();
							if (filter.matches(Collections.singletonMap("filename", pathStr))) //$NON-NLS-1$
								files.add((IFile) resource);

							return false;
						}

						return true;
					}
				});
			} else {
				files.add(PDEProject.getBundleRelativeFile(project, new Path(element)));
			}

			// process each descriptor file
			monitor.beginTask(project.getName(), files.size());
			try {
				for (IFile file : files) {
					if (monitor.isCanceled())
						throw new OperationCanceledException();

					searchFile(file, new SubProgressMonitor(monitor, 1));
				}
			} finally {
				monitor.done();
			}
		}
	}

	private String sanitizeFilterValue(String value) {
		return value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
	}

	private void searchFile(IFile file, IProgressMonitor monitor) throws CoreException {
		monitor.subTask(file.getName());

		String content = null;
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
			InputStream in = file.getContents();
			try {
				byte[] buf = new byte[4096];
				int c;
				while ((c = in.read(buf)) != -1) {
					out.write(buf, 0, c);
				}

				content = out.toString(file.getCharset());
			} finally {
				in.close();
			}
		} catch (IOException e) {
			Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID, String.format("Error loading component descriptor file: %s", file.getFullPath()), e)); //$NON-NLS-1$
		}

		Document doc = new Document(content);
		DSModel dsModel = new DSModel(doc, false);
		try {
			dsModel.setUnderlyingResource(file);
			dsModel.setCharset(file.getCharset());
			dsModel.load();

			IDSComponent component = dsModel.getDSComponent();
			if (component == null) {
				if (debug.isDebugging())
					debug.trace(String.format("No component definition found in file: %s", file.getFullPath())); //$NON-NLS-1$

				return;
			}

			IDSImplementation impl = component.getImplementation();
			if (impl == null) {
				if (debug.isDebugging())
					debug.trace(String.format("No component implementation found in file: %s", file.getFullPath())); //$NON-NLS-1$

				return;
			}

			IJavaProject javaProject = JavaCore.create(file.getProject());

			IType implClassType = null;
			String implClassName = impl.getClassName();
			if (implClassName != null)
				implClassType = javaProject.findType(implClassName, monitor);

			if ((searchElement != null && searchElement.getElementType() == IJavaElement.TYPE)
					|| searchFor == IJavaSearchConstants.TYPE
					|| searchFor == IJavaSearchConstants.CLASS
					|| searchFor == IJavaSearchConstants.CLASS_AND_INTERFACE
					|| searchFor == IJavaSearchConstants.CLASS_AND_ENUM
					|| searchFor == IJavaSearchConstants.UNKNOWN) {
				// match specific type references
				if (matches(searchElement, searchPattern, implClassType))
					reportMatch(requestor, impl.getDocumentAttribute(IDSConstants.ATTRIBUTE_IMPLEMENTATION_CLASS), file);
			}

			if ((searchElement != null && searchElement.getElementType() == IJavaElement.TYPE)
					|| searchFor == IJavaSearchConstants.TYPE
					|| searchFor == IJavaSearchConstants.CLASS
					|| searchFor == IJavaSearchConstants.CLASS_AND_INTERFACE
					|| searchFor == IJavaSearchConstants.CLASS_AND_ENUM
					|| searchFor == IJavaSearchConstants.INTERFACE
					|| searchFor == IJavaSearchConstants.INTERFACE_AND_ANNOTATION
					|| searchFor == IJavaSearchConstants.UNKNOWN) {
				IDSService service = component.getService();
				if (service != null) {
					IDSProvide[] provides = service.getProvidedServices();
					if (provides != null) {
						for (IDSProvide provide : provides) {
							String ifaceName = provide.getInterface();
							IType ifaceType = javaProject.findType(ifaceName, monitor);
							if (matches(searchElement, searchPattern, ifaceType))
								reportMatch(requestor, provide.getDocumentAttribute(IDSConstants.ATTRIBUTE_PROVIDE_INTERFACE), file);
						}
					}
				}

				IDSReference[] references = component.getReferences();
				if (references != null) {
					for (IDSReference reference : references) {
						String ifaceName = reference.getReferenceInterface();
						IType ifaceType = javaProject.findType(ifaceName, monitor);
						if (matches(searchElement, searchPattern, ifaceType))
							reportMatch(requestor, reference.getDocumentAttribute(IDSConstants.ATTRIBUTE_REFERENCE_INTERFACE), file);
					}
				}
			}

			if ((searchElement != null && searchElement.getElementType() == IJavaElement.METHOD)
					|| searchFor == IJavaSearchConstants.METHOD
					|| searchFor == IJavaSearchConstants.UNKNOWN) {
				// match specific method references
				String activate = component.getActivateMethod();
				if (activate == null)
					activate = "activate"; //$NON-NLS-1$

				IMethod activateMethod = findActivateMethod(implClassType, activate, monitor);
				if (matches(searchElement, searchPattern, activateMethod)) {
					if (component.getActivateMethod() == null)
						reportMatch(requestor, component, file);
					else
						reportMatch(requestor, component.getDocumentAttribute(IDSConstants.ATTRIBUTE_COMPONENT_ACTIVATE), file);
				}

				String modified = component.getModifiedMethod();
				if (modified != null) {
					IMethod modifiedMethod = findActivateMethod(implClassType, modified, monitor);
					if (matches(searchElement, searchPattern, modifiedMethod))
						reportMatch(requestor, component.getDocumentAttribute(IDSConstants.ATTRIBUTE_COMPONENT_MODIFIED), file);
				}

				String deactivate = component.getDeactivateMethod();
				if (deactivate == null)
					deactivate = "deactivate"; //$NON-NLS-1$

				IMethod deactivateMethod = findDeactivateMethod(implClassType, deactivate, monitor);
				if (matches(searchElement, searchPattern, deactivateMethod)) {
					if (component.getDeactivateMethod() == null)
						reportMatch(requestor, component, file);
					else
						reportMatch(requestor, component.getDocumentAttribute(IDSConstants.ATTRIBUTE_COMPONENT_DEACTIVATE), file);
				}

				IDSReference[] references = component.getReferences();
				if (references != null) {
					for (IDSReference reference : references) {
						String refIface = reference.getReferenceInterface();
						if (refIface == null) {
							if (debug.isDebugging())
								debug.trace(String.format("No reference interface specified: %s", reference)); //$NON-NLS-1$

							continue;
						}

						String bind = reference.getReferenceBind();
						if (bind != null) {
							IMethod bindMethod = findReferenceMethod(implClassType, bind, refIface, monitor);
							if (matches(searchElement, searchPattern, bindMethod))
								reportMatch(requestor, reference.getDocumentAttribute(IDSConstants.ATTRIBUTE_REFERENCE_BIND), file);
						}

						String unbind = reference.getReferenceUnbind();
						if (unbind != null) {
							IMethod unbindMethod = findReferenceMethod(implClassType, unbind, refIface, monitor);
							if (matches(searchElement, searchPattern, unbindMethod))
								reportMatch(requestor, reference.getDocumentAttribute(IDSConstants.ATTRIBUTE_REFERENCE_UNBIND), file);
						}

						String updated = reference.getXMLAttributeValue("updated"); //$NON-NLS-1$
						if (updated != null) {
							IMethod updatedMethod = findReferenceMethod(implClassType, updated, refIface, monitor);
							if (matches(searchElement, searchPattern, updatedMethod))
								reportMatch(requestor, reference.getDocumentAttribute("updated"), file); //$NON-NLS-1$
						}
					}
				}
			}
		} finally {
			dsModel.dispose();
		}
	}

	private IMethod findActivateMethod(IType implClassType, String name, IProgressMonitor monitor) throws JavaModelException {
		IMethod candidate = null;
		int priority = Integer.MAX_VALUE;
		boolean first = true;

		IType type = implClassType;
		while (type != null) {
			for (IMethod method : type.getMethods()) {
				if (!name.equals(method.getElementName()))
					continue;

				if (!VOID_SIG.equals(method.getReturnType()))
					continue;

				if (!first
						&& (Flags.isPrivate(method.getFlags())
								|| (Flags.isPackageDefault(method.getFlags())
										&& !implClassType.getPackageFragment().equals(method.getDeclaringType().getPackageFragment()))))
					continue;

				String[] paramSigs = resolveParameterTypes(method);

				if (paramSigs.length == 1 && COMPONENT_CONTEXT_SIG.equals(paramSigs[0])) {
					// best match
					return method;
				}

				if (priority > 1 && paramSigs.length == 1 && COMPONENT_CONTEXT_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 1;
					continue;
				}

				if (priority > 2 && paramSigs.length == 1 && MAP_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 2;
					continue;
				}

				if (priority > 3 && paramSigs.length >= 2) {
					boolean valid = true;
					for (String paramSig : paramSigs) {
						if (!COMPONENT_CONTEXT_SIG.equals(paramSig)
								&& !BUNDLE_CONTEXT_SIG.equals(paramSig)
								&& !MAP_SIG.equals(paramSig)) {
							valid = false;
							break;
						}
					}

					if (valid) {
						candidate = method;
						priority = 3;
					}

					continue;
				}

				if (priority > 4 && paramSigs.length == 0) {
					candidate = method;
					priority = 4;
					continue;
				}
			}

			first = false;

			type = findSuperclassType(type, implClassType.getJavaProject(), monitor);
		}

		return candidate;
	}

	private IType findSuperclassType(IType type, IJavaProject project, IProgressMonitor monitor) throws JavaModelException, IllegalArgumentException {
		String superSig = type.getSuperclassTypeSignature();
		if (superSig == null)
			return null;

		String superName = Signature.toString(Signature.getTypeErasure(superSig));
		if (type.isResolved())
			return project.findType(superName, monitor);

		String[][] resolvedNames = type.resolveType(superName);
		if (resolvedNames == null || resolvedNames.length == 0)
			return null;

		return project.findType(resolvedNames[0][0], resolvedNames[0][1], monitor);
	}

	private IMethod findDeactivateMethod(IType implClassType, String name, IProgressMonitor monitor) throws JavaModelException {
		IMethod candidate = null;
		int priority = Integer.MAX_VALUE;
		boolean first = true;

		IType type = implClassType;
		while (type != null) {
			for (IMethod method : type.getMethods()) {
				if (!name.equals(method.getElementName()))
					continue;

				if (!VOID_SIG.equals(method.getReturnType()))
					continue;

				if (!first
						&& (Flags.isPrivate(method.getFlags())
								|| (Flags.isPackageDefault(method.getFlags())
										&& !implClassType.getPackageFragment().equals(method.getDeclaringType().getPackageFragment()))))
					continue;

				String[] paramSigs = resolveParameterTypes(method);

				if (paramSigs.length == 1 && COMPONENT_CONTEXT_SIG.equals(paramSigs[0])) {
					// best match
					return method;
				}

				if (priority > 1 && paramSigs.length == 1 && COMPONENT_CONTEXT_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 1;
					continue;
				}

				if (priority > 2 && paramSigs.length == 1 && MAP_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 2;
					continue;
				}

				if (priority > 3 && paramSigs.length == 1 && INT_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 3;
					continue;
				}

				if (priority > 4 && paramSigs.length == 1 && INTEGER_SIG.equals(paramSigs[0])) {
					candidate = method;
					priority = 4;
					continue;
				}

				if (priority > 5 && paramSigs.length >= 2) {
					boolean valid = true;
					for (String paramSig : paramSigs) {
						if (!COMPONENT_CONTEXT_SIG.equals(paramSig)
								&& !BUNDLE_CONTEXT_SIG.equals(paramSig)
								&& !MAP_SIG.equals(paramSig)
								&& !INT_SIG.equals(paramSig)
								&& !INTEGER_SIG.equals(paramSig)) {
							valid = false;
							break;
						}
					}

					if (valid) {
						candidate = method;
						priority = 5;
					}

					continue;
				}

				if (priority > 6 && paramSigs.length == 0) {
					candidate = method;
					priority = 6;
					continue;
				}
			}

			first = false;

			type = findSuperclassType(type, implClassType.getJavaProject(), monitor);
		}

		return candidate;
	}

	private IMethod findReferenceMethod(IType implClassType, String name, String referenceTypeName, IProgressMonitor monitor) throws JavaModelException {
		IMethod candidate = null;
		int priority = Integer.MAX_VALUE;
		boolean first = true;

		String referenceTypeSig = Signature.createTypeSignature(referenceTypeName, true);
		IType referenceType = null;
		IType arg0Type = null;

		IType type = implClassType;
		while (type != null) {
			for (IMethod method : type.getMethods()) {
				if (!name.equals(method.getElementName()))
					continue;

				if (!VOID_SIG.equals(method.getReturnType()))
					continue;

				if (!first
						&& (Flags.isPrivate(method.getFlags())
								|| (Flags.isPackageDefault(method.getFlags())
										&& !implClassType.getPackageFragment().equals(method.getDeclaringType().getPackageFragment()))))
					continue;

				String[] paramSigs = resolveParameterTypes(method);

				if (paramSigs.length == 1 && SERVICE_REFERENCE_SIG.equals(paramSigs[0])) {
					// best match
					return method;
				}

				if (priority > 1 && paramSigs.length == 1 && referenceTypeSig.equals(paramSigs[0])) {
					candidate = method;
					priority = 1;
					continue;
				}

				if (priority > 2 && paramSigs.length == 1) {
					if (referenceType == null)
						referenceType = implClassType.getJavaProject().findType(referenceTypeName, monitor);

					if (arg0Type == null)
						arg0Type = implClassType.getJavaProject().findType(Signature.toString(paramSigs[0]), monitor);

					if (isAssignableFrom(arg0Type, referenceType, monitor)) {
						candidate = method;
						priority = 2;
					}

					continue;
				}

				if (priority > 3 && paramSigs.length == 2 && referenceTypeSig.equals(paramSigs[0]) && MAP_SIG.equals(paramSigs[1])) {
					candidate = method;
					priority = 3;
					continue;
				}

				if (priority > 4 && paramSigs.length == 2 && MAP_SIG.equals(paramSigs[1])) {
					if (referenceType == null)
						referenceType = implClassType.getJavaProject().findType(referenceTypeName, monitor);

					if (arg0Type == null)
						arg0Type = implClassType.getJavaProject().findType(Signature.toString(paramSigs[0]), monitor);

					if (isAssignableFrom(arg0Type, referenceType, monitor)) {
						candidate = method;
						priority = 4;
					}

					continue;
				}
			}

			first = false;

			type = findSuperclassType(type, implClassType.getJavaProject(), monitor);
		}

		return candidate;
	}

	private String[] resolveParameterTypes(IMethod method) throws JavaModelException {
		String[] paramSigs = method.getParameterTypes();
		paramSigs = Arrays.copyOf(method.getParameterTypes(), paramSigs.length);
		for (int i = 0; i < paramSigs.length; ++i) {
			paramSigs[i] = Signature.getTypeErasure(paramSigs[i]);
			if (!method.isResolved()) {
				String[][] resolvedParamTypes = method.getDeclaringType().resolveType(Signature.toString(paramSigs[i]));
				if (resolvedParamTypes != null && resolvedParamTypes.length > 0) {
					// TODO should we use all results??
					paramSigs[i] = Signature.createTypeSignature(resolvedParamTypes[0][0].isEmpty() ? resolvedParamTypes[0][1] : String.format("%s.%s", resolvedParamTypes[0][0], resolvedParamTypes[0][1]), true); //$NON-NLS-1$
				}
			}
		}

		return paramSigs;
	}

	private boolean isAssignableFrom(IType type, IType subtype, IProgressMonitor monitor) throws JavaModelException {
		if (subtype == null)
			return false;

		IJavaProject project = subtype.getJavaProject();
		while (type != null) {
			if (type.equals(subtype))
				return true;

			type = findSuperclassType(type, project, monitor);
		}

		return false;
	}

	private boolean matches(IJavaElement element, SearchPatternDescriptor pattern, IType type) {
		if (type == null)
			return false;

		if (element != null)
			return element.equals(type);

		return pattern.matches(type);
	}

	private boolean matches(IJavaElement element, SearchPatternDescriptor pattern, IMethod method) throws JavaModelException {
		if (method == null)
			return false;

		if (element != null)
			return element.equals(method);

		return pattern.matches(method);
	}

	private void reportMatch(ISearchRequestor requestor, IDocumentAttributeNode attr, IFile file) {
		requestor.reportMatch(new Match(file, attr.getValueOffset(), attr.getValueLength()));
	}

	private void reportMatch(ISearchRequestor requestor, IDocumentElementNode elem, IFile file) {
		String prefix = elem.getNamespacePrefix();
		int nameLen = prefix == null || prefix.isEmpty() ? elem.getXMLTagName().length() : prefix.length() + elem.getXMLTagName().length() + 1;
		requestor.reportMatch(new Match(file, elem.getOffset() + 1, nameLen));
	}

	public int estimateTicks(QuerySpecification specification) {
		return 100;
	}

	public IMatchPresentation getUIParticipant() {
		return null;
	}

	private static class SearchPatternDescriptor {

		private final String pattern;

		private final int matchRule;

		private final boolean simple;

		private final boolean ignoreMethodParams;

		public SearchPatternDescriptor(String pattern, int matchRule, boolean ignoreMethodParams) {
			this.pattern = pattern;
			this.matchRule = matchRule & ~SearchPattern.R_ERASURE_MATCH;
			this.ignoreMethodParams = ignoreMethodParams;
			simple = Signature.getQualifier(pattern).isEmpty();
		}

		public boolean matches(IType type) {
			String name = type.getFullyQualifiedName('.');
			return matches(simple ? Signature.getSimpleName(name) : name);
		}

		public boolean matches(IMethod method) throws JavaModelException {
			String simpleName = ignoreMethodParams ? method.getElementName() : Signature.toString(method.getSignature(), method.getElementName(), null, true, false, Flags.isVarargs(method.getFlags()));
			return matches(simple ? simpleName : String.format("%s.%s", method.getDeclaringType().getFullyQualifiedName(), simpleName)); //$NON-NLS-1$
		}

		public boolean matches(String name) {
			return SearchPattern.getMatchingRegions(pattern, name, matchRule) != null;
		}
	}
}
