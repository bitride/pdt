/*******************************************************************************
 * Copyright (c) 2017 Alex Xu and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Alex Xu - initial API and implementation
 *******************************************************************************/
package org.eclipse.php.internal.server.core.builtin.xml;

public class Port extends XMLElement {

	public String getName() {
		return getAttributeValue("name"); //$NON-NLS-1$
	}

	public String getProtocol() {
		return getAttributeValue("protocol"); //$NON-NLS-1$
	}

	public int getPort() {
		int port = -1;
		try {
			port = Integer.parseInt(getElementValue());
		} catch (Exception e) {
			// ignore
		}
		return port;
	}

	public void setPort(int port) {
		setElementValue(getElementNode(), String.valueOf(port));
	}

}
