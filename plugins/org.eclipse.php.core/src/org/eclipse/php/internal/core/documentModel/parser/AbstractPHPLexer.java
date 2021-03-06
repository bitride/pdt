/*******************************************************************************
 * Copyright (c) 2009, 2016, 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Zend Technologies
 *******************************************************************************/
package org.eclipse.php.internal.core.documentModel.parser;

import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;

import javax.swing.text.Segment;

import org.apache.commons.lang3.ArrayUtils;
import org.eclipse.dltk.annotations.NonNull;
import org.eclipse.dltk.annotations.Nullable;
import org.eclipse.php.internal.core.documentModel.parser.regions.PHPRegionTypes;
import org.eclipse.php.internal.core.documentModel.partitioner.PHPPartitionTypes;
import org.eclipse.php.internal.core.util.collections.StateStack;
import org.eclipse.wst.sse.core.internal.parser.ContextRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegion;

public abstract class AbstractPHPLexer implements Scanner, PHPRegionTypes {

	// should be auto-generated by jflex
	protected abstract int getZZEndRead();

	protected abstract int getZZLexicalState();

	protected abstract int getZZMarkedPos();

	protected abstract int getZZPushBackPosition();

	protected abstract int getZZStartRead();

	protected abstract void pushBack(int i);

	public abstract char[] getZZBuffer();

	public abstract void yybegin(int newState);

	public abstract int yylength();

	public abstract String yytext();

	protected abstract void reset(Reader reader, char[] buffer, int[] parameters);

	public abstract int yystate();

	public abstract int getInScriptingState();

	public abstract int[] getHeredocStates();

	public abstract int[] getPHPQuotesStates();

	public abstract int[] getParameters();

	public abstract int getScriptingState();

	protected static final boolean isLowerCase(final String text) {
		if (text == null)
			return false;
		for (int i = 0; i < text.length(); i++)
			if (!Character.isLowerCase(text.charAt(i)))
				return false;
		return true;
	}

	protected boolean asp_tags = true;

	protected StateStack phpStack;
	// https://bugs.eclipse.org/bugs/show_bug.cgi?id=514632
	// stores nested HEREDOC and NOWDOC ids
	protected String[] heredocIds;

	/**
	 * build a state that represents the current state of the lexer.
	 */
	private LexerState buildLexerState() {
		LexerState state = new BasicLexerState(this);
		if (ArrayUtils.contains(getHeredocStates(), getZZLexicalState())) {
			state = new HeredocState((BasicLexerState) state, this);
		}
		return state;
	}

	@SuppressWarnings("null")
	public @NonNull LexerState createLexicalStateMemento() {
		// buffered token state
		if (bufferedTokens != null && !bufferedTokens.isEmpty()) {
			assert bufferedState != null;
			return bufferedState;
		}
		LexerState currentState = buildLexerState();
		LexerState cachedState = getLexerStates().get(currentState);
		if (cachedState != null) {
			return cachedState;
		}
		getLexerStates().put(currentState, currentState);
		return currentState;
	}

	// A pool of states. To avoid creation of a new state on each createMemento.
	protected abstract Map<LexerState, LexerState> getLexerStates();

	public boolean getAspTags() {
		return asp_tags;
	}

	// lex to the EOF. and return the ending state.
	public @NonNull LexerState getEndingState() throws IOException {
		lexToEnd();
		return createLexicalStateMemento();
	}

	public int getMarkedPos() {
		return getZZMarkedPos();
	}

	public void getText(final int start, final int length, final Segment s) {
		if (start + length > getZZEndRead())
			throw new RuntimeException("bad segment !!"); //$NON-NLS-1$
		s.array = getZZBuffer();
		s.offset = start;
		s.count = length;
	}

	public int getTokenStart() {
		return getZZStartRead() - getZZPushBackPosition();
	}

	public void initialize(final int state) {
		phpStack = new StateStack();
		heredocIds = null;

		bufferedTokens = null;
		bufferedLength = 0;
		bufferedState = null;

		yybegin(state);
	}

	/**
	 * reset to a new segment. this do not change the state of the lexer. This
	 * method is used to scan more than one segment as if the are one segment.
	 */

	// lex to the end of the stream.
	public @Nullable String lexToEnd() throws IOException {
		String curr = yylex();
		String last = curr;
		while (curr != null) {
			last = curr;
			curr = yylex();
		}
		return last;
	}

	protected void popState() {
		yybegin(phpStack.popStack());
	}

	protected void pushState(final int state) {
		phpStack.pushStack(getZZLexicalState());
		yybegin(state);
	}

	protected void pushHeredocId(final String heredocId) {
		if (heredocIds == null) {
			heredocIds = new String[] { heredocId };
			return;
		}
		assert heredocIds.length != 0;
		String[] newHeredocIds = new String[heredocIds.length + 1];
		System.arraycopy(heredocIds, 0, newHeredocIds, 0, heredocIds.length);
		newHeredocIds[heredocIds.length] = heredocId;
		heredocIds = newHeredocIds;
	}

	protected String getHeredocId() {
		if (heredocIds == null) {
			return null;
		}
		assert heredocIds.length != 0;
		return heredocIds[heredocIds.length - 1];
	}

	protected void popHeredocId() {
		if (heredocIds == null) {
			return;
		}
		assert heredocIds.length != 0;
		if (heredocIds.length == 1) {
			heredocIds = null;
			return;
		}
		String[] newHeredocIds = new String[heredocIds.length - 1];
		System.arraycopy(heredocIds, 0, newHeredocIds, 0, heredocIds.length - 1);
		heredocIds = newHeredocIds;
	}

	public void setAspTags(final boolean b) {
		asp_tags = b;
	}

	public void setState(final Object state) {
		((LexerState) state).restoreState(this);
	}

	public int yystart() {
		return getZZStartRead();
	}

	public LinkedList<ITextRegion> bufferedTokens = null;
	public int bufferedLength = 0;
	public LexerState bufferedState = null;

	/**
	 * @return the next token from the php lexer
	 * @throws IOException
	 */
	public @Nullable String getNextToken() throws IOException {
		if (bufferedTokens != null) {
			if (bufferedTokens.isEmpty()) {
				bufferedTokens = null;
				bufferedLength = 0;
			} else {
				return removeFromBuffer();
			}
		}

		bufferedState = createLexicalStateMemento();
		String yylex = yylex();
		if (PHPPartitionTypes.isPHPDocRegion(yylex)) {
			final StringBuilder buffer = new StringBuilder();
			int length = 0;
			while (PHPPartitionTypes.isPHPDocRegion(yylex)) {
				buffer.append(yytext());
				length += yylength();
				yylex = yylex();
			}
			bufferedTokens = new LinkedList<ITextRegion>();
			bufferedTokens.add(new ContextRegion(PHPRegionTypes.PHPDOC_COMMENT, 0, length, length));
			if (yylex != null) {
				bufferedTokens.add(new ContextRegion(yylex, 0, yylength(), yylength()));
			}
			yylex = removeFromBuffer();
		} else if (PHPPartitionTypes.isPHPCommentState(yylex)) {
			bufferedTokens = new LinkedList<ITextRegion>();
			bufferedTokens.add(new ContextRegion(yylex, 0, yylength(), yylength()));
			yylex = removeFromBuffer();
		}

		if (yylex == PHP_CLOSETAG) {
			pushBack(getLength());
		}

		return yylex;
	}

	/**
	 * @return the last token from buffer
	 */
	private String removeFromBuffer() {
		ITextRegion region = (ITextRegion) bufferedTokens.removeFirst();
		bufferedLength = region.getLength();
		return region.getType();
	}

	public int getLength() {
		return bufferedTokens == null ? yylength() : bufferedLength;
	}

	private static class BasicLexerState implements LexerState {

		private final byte lexicalState;
		private StateStack phpStack;

		public BasicLexerState(AbstractPHPLexer lexer) {
			if (!lexer.phpStack.isEmpty()) {
				phpStack = lexer.phpStack.createClone();
			}
			lexicalState = (byte) lexer.getZZLexicalState();
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + lexicalState;
			result = prime * result + ((phpStack == null) ? 0 : phpStack.hashCode());
			return result;
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			BasicLexerState other = (BasicLexerState) obj;
			if (lexicalState != other.lexicalState)
				return false;
			if (phpStack == null) {
				if (other.phpStack != null)
					return false;
			} else if (!phpStack.equals(other.phpStack))
				return false;
			return true;
		}

		public boolean equalsCurrentStack(final LexerState obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			BasicLexerState other = (BasicLexerState) obj;
			if (lexicalState != other.lexicalState)
				return false;
			final StateStack activeStack = getActiveStack();
			final StateStack otherActiveStack = other.getActiveStack();
			if (!(activeStack == otherActiveStack || activeStack != null && activeStack.equals(otherActiveStack)))
				return false;
			return true;
		}

		public boolean equalsTop(final LexerState obj) {
			return obj != null && obj.getTopState() == lexicalState;
		}

		// IMPORTANT: do *NOT* modify the active stack once it is stored in an
		// BasicLexerState object
		protected StateStack getActiveStack() {
			return phpStack;
		}

		public int getTopState() {
			return lexicalState;
		}

		public boolean isSubstateOf(final int state) {
			if (lexicalState == state)
				return true;
			final StateStack activeStack = getActiveStack();
			if (activeStack == null)
				return false;
			return activeStack.contains(state);
		}

		public void restoreState(final Scanner scanner) {
			final AbstractPHPLexer lexer = (AbstractPHPLexer) scanner;

			if (phpStack == null)
				lexer.phpStack.clear();
			else
				lexer.phpStack.copyFrom(phpStack);

			lexer.yybegin(lexicalState);
		}

		@Override
		public String toString() {
			final StateStack stack = getActiveStack();
			final String stackStr = stack == null ? "null" : stack.toString(); //$NON-NLS-1$
			return "Stack: " + stackStr + ", currState: " + lexicalState; //$NON-NLS-1$ //$NON-NLS-2$
		}

	}

	private static class HeredocState implements LexerState {
		// https://bugs.eclipse.org/bugs/show_bug.cgi?id=514632
		// stores nested HEREDOC and NOWDOC ids
		private final String[] heredocIds;
		private final BasicLexerState theState;

		public HeredocState(final BasicLexerState state, AbstractPHPLexer lexer) {
			theState = state;
			heredocIds = lexer.heredocIds;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(heredocIds);
			result = prime * result + ((theState == null) ? 0 : theState.hashCode());
			return result;
		}

		@Override
		public boolean equals(final Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			HeredocState other = (HeredocState) obj;
			if (!Arrays.equals(heredocIds, other.heredocIds))
				return false;
			if (theState == null) {
				if (other.theState != null)
					return false;
			} else if (!theState.equals(other.theState))
				return false;
			return true;
		}

		public boolean equalsCurrentStack(final LexerState obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			return theState.equals(((HeredocState) obj).theState);
		}

		public boolean equalsTop(final LexerState obj) {
			return theState.equalsTop(obj);
		}

		public int getTopState() {
			return theState.getTopState();
		}

		public boolean isSubstateOf(final int state) {
			return theState.isSubstateOf(state);
		}

		public void restoreState(final Scanner scanner) {
			final AbstractPHPLexer lexer = (AbstractPHPLexer) scanner;
			theState.restoreState(lexer);
			lexer.heredocIds = heredocIds.length == 0 ? null : heredocIds;
		}
	}
}
