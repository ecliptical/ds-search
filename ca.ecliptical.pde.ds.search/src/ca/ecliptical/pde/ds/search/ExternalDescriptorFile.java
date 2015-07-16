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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.core.runtime.Status;

public class ExternalDescriptorFile extends PlatformObject implements IStorage {

	private final IPath filePath;

	private final URL url;

	public ExternalDescriptorFile(IPath filePath, URL url) {
		this.filePath = filePath.makeAbsolute();
		this.url = url;
	}

	public InputStream getContents() throws CoreException {
		try {
			return url.openStream();
		} catch (IOException e) {
			throw new CoreException(new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Error reading storage contents.", e)); //$NON-NLS-1$
		}
	}

	public IPath getFullPath() {
		return filePath;
	}

	public String getName() {
		return getFullPath().lastSegment();
	}

	public boolean isReadOnly() {
		return true;
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;

		if (!(obj instanceof ExternalDescriptorFile))
			return false;

		return url.equals(((ExternalDescriptorFile) obj).url);
	}

	@Override
	public int hashCode() {
		return url.hashCode();
	}

	@Override
	public String toString() {
		return url.toString();
	}
}
