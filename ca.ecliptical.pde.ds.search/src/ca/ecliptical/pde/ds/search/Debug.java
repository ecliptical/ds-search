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

import org.eclipse.osgi.service.debug.DebugOptions;
import org.eclipse.osgi.service.debug.DebugTrace;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

public class Debug {

	private final DebugTrace trace;

	private final String option;

	private Debug(DebugTrace trace, String option) {
		this.trace = trace;
		this.option = option;
	}

	public static Debug getDebug(String name) {
		if (!name.startsWith("/")) //$NON-NLS-1$
			name = "/" + name; //$NON-NLS-1$

		BundleContext ctx = Activator.getDefault().getBundle().getBundleContext();
		ServiceReference<DebugOptions> ref = ctx.getServiceReference(DebugOptions.class);
		DebugOptions options = null;
		DebugTrace trace = null;
		if (ref != null) {
			options = ctx.getService(ref);
			if (options.isDebugEnabled() && options.getBooleanOption(Activator.PLUGIN_ID + name, false)) {
				trace = options.newDebugTrace(Activator.PLUGIN_ID);
			}

			ctx.ungetService(ref);
		}

		return new Debug(trace, name);
	}

	public boolean isDebugging() {
		return trace != null;
	}

	public void trace(String message) {
		if (trace != null)
			trace.trace(option, message);
	}

	public void trace(String message, Throwable error) {
		if (trace != null)
			trace.trace(message, message, error);
	}

	public void traceDumpStack() {
		if (trace != null)
			trace.traceDumpStack(option);
	}

	public void traceEntry() {
		if (trace != null)
			trace.traceEntry(option);
	}

	public void traceEntry(Object methodArgument) {
		if (trace != null)
			trace.traceEntry(option, methodArgument);
	}

	public void traceEntry(Object[] methodArguments) {
		if (trace != null)
			trace.traceEntry(option, methodArguments);
	}

	public void traceExit() {
		if (trace != null)
			trace.traceExit(option);
	}

	public void traceExit(Object result) {
		if (trace != null)
			trace.traceExit(option, result);
	}
}
