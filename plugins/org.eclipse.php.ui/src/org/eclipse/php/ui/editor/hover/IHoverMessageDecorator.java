/*******************************************************************************
 * Copyright (c) 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Zend Technologies
 *******************************************************************************/
package org.eclipse.php.ui.editor.hover;

/**
 * Decorates hover message
 * 
 * @author eden
 */
public interface IHoverMessageDecorator {

	/**
	 * Gets the hover message and returns the decorated format
	 * 
	 * @param msg
	 *            (can be null)
	 */
	public String getDecoratedMessage(String msg);

}
