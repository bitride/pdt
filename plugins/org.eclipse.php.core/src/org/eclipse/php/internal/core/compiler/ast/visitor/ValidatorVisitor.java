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
package org.eclipse.php.internal.core.compiler.ast.visitor;

import java.text.MessageFormat;
import java.util.*;
import java.util.regex.Matcher;

import org.eclipse.dltk.ast.ASTNode;
import org.eclipse.dltk.ast.declarations.ModuleDeclaration;
import org.eclipse.dltk.ast.declarations.TypeDeclaration;
import org.eclipse.dltk.ast.references.TypeReference;
import org.eclipse.dltk.ast.statements.Statement;
import org.eclipse.dltk.compiler.problem.*;
import org.eclipse.dltk.core.*;
import org.eclipse.dltk.core.builder.IBuildContext;
import org.eclipse.dltk.core.builder.ISourceLineTracker;
import org.eclipse.php.core.PHPVersion;
import org.eclipse.php.core.compiler.PHPFlags;
import org.eclipse.php.core.compiler.ast.nodes.*;
import org.eclipse.php.core.compiler.ast.visitor.PHPASTVisitor;
import org.eclipse.php.core.project.ProjectOptions;
import org.eclipse.php.internal.core.PHPCorePlugin;
import org.eclipse.php.internal.core.codeassist.PHPSelectionEngine;
import org.eclipse.php.internal.core.compiler.ast.parser.Messages;
import org.eclipse.php.internal.core.compiler.ast.parser.PHPProblemIdentifier;
import org.eclipse.php.internal.core.typeinference.PHPModelUtils;
import org.eclipse.php.internal.core.typeinference.PHPSimpleTypes;
import org.eclipse.php.internal.core.typeinference.evaluators.PHPEvaluationUtils;
import org.eclipse.php.internal.core.util.text.PHPTextSequenceUtilities;

public class ValidatorVisitor extends PHPASTVisitor {

	private static final String PAAMAYIM_NEKUDOTAIM = "::"; //$NON-NLS-1$
	private static final List<String> TYPE_SKIP = new ArrayList<String>();
	private static final List<String> PHPDOC_TYPE_SKIP = new ArrayList<String>();

	static {
		TYPE_SKIP.add("parent"); //$NON-NLS-1$
		TYPE_SKIP.add("self"); //$NON-NLS-1$
		TYPE_SKIP.add("static"); //$NON-NLS-1$
		TYPE_SKIP.add("null"); //$NON-NLS-1$
		PHPDOC_TYPE_SKIP.add("true"); //$NON-NLS-1$
		PHPDOC_TYPE_SKIP.add("false"); //$NON-NLS-1$
	}

	private Map<String, UsePartInfo> usePartInfo = new LinkedHashMap<String, UsePartInfo>();
	private Map<String, Boolean> elementExists = new HashMap<String, Boolean>();
	private NamespaceDeclaration currentNamespace;
	private Set<String> typeDeclared = new HashSet<>();
	private boolean hasNamespace;
	private ISourceModule sourceModule;
	private PHPVersion version;
	private IBuildContext context;

	@SuppressWarnings("null")
	public ValidatorVisitor(IBuildContext context) {
		this.context = context;
		this.sourceModule = context.getSourceModule();
		this.version = ProjectOptions.getPHPVersion(sourceModule);
	}

	@Override
	public boolean endvisit(ModuleDeclaration s) throws Exception {
		if (!hasNamespace) {
			checkUnusedImport();
		}
		return super.endvisit(s);
	}

	@Override
	public boolean visit(NamespaceDeclaration s) throws Exception {
		hasNamespace = true;
		currentNamespace = s;
		return true;
	}

	@Override
	public boolean endvisit(NamespaceDeclaration s) throws Exception {
		checkUnusedImport();
		return super.endvisit(s);
	}

	@Override
	public boolean visit(PHPMethodDeclaration s) throws Exception {
		if (s.getPHPDoc() != null)
			s.getPHPDoc().traverse(this);
		return visitGeneral(s);
	}

	@Override
	public boolean visit(PHPFieldDeclaration s) throws Exception {
		if (s.getPHPDoc() != null) {
			s.getPHPDoc().traverse(this);
		}
		return super.visit(s);
	}

	@Override
	public boolean visit(PHPCallExpression node) throws Exception {
		if (node.getReceiver() != null) {
			node.getReceiver().traverse(this);
		}
		if (node.getArgs() != null) {
			node.getArgs().traverse(this);
		}
		return false;
	}

	@Override
	public boolean visit(FullyQualifiedReference node) throws Exception {
		if (node.getElementType() == FullyQualifiedReference.T_TYPE) {
			return visit((TypeReference) node);
		}
		return super.visit(node);
	}

	@Override
	public boolean visit(TypeReference node) throws Exception {
		return visit(node, ProblemSeverities.Error, false);
	}

	private boolean visit(TypeReference node, ProblemSeverity severity, boolean isInDoc) throws Exception {
		boolean skip = false;
		if (isInDoc) {
			skip = PHPSimpleTypes.isSimpleType(node.getName());
		} else {
			skip = PHPSimpleTypes.isHintable(node.getName(), version);
		}
		if (skip || TYPE_SKIP.contains(node.getName().toLowerCase())) {
			return true;
		}
		TypeReferenceInfo tri = new TypeReferenceInfo(node, false);
		String nodeName = tri.getTypeName();
		String key = null;
		if (tri.isGlobal()) {
			key = nodeName;
		} else {
			key = getFirstSegmentOfTypeName(nodeName);
		}
		UsePartInfo info = usePartInfo.get(key.toLowerCase());

		if (info != null) {
			info.refer();
		}
		boolean isFound = findElement(tri);
		if (!isFound) {
			reportProblem(node, Messages.UndefinedType, PHPProblemIdentifier.UndefinedType, node.getName(), severity);
		}
		return false;
	}

	@Override
	public boolean visit(AnonymousClassDeclaration s) throws Exception {
		int end = s.start();
		int start = end - 1;
		while (sourceModule.getSource().charAt(start) != ' ') {
			start--;
		}
		checkUnimplementedMethods(s, start + 1, end);

		IModelElement element = sourceModule.getElementAt(s.start());
		if (s.getSuperClass() != null && element != null)
			checkSuperclass(s.getSuperClass(), false, element.getElementName());

		Collection<TypeReference> interfaces = s.getInterfaceList();
		if (interfaces != null && element != null) {
			for (TypeReference itf : interfaces)
				checkSuperclass(itf, true, element.getElementName());
		}
		return super.visit(s);
	}

	@Override
	public boolean visit(ClassDeclaration s) throws Exception {
		checkUnimplementedMethods(s, s.getRef());
		if (s.getSuperClass() != null)
			checkSuperclass(s.getSuperClass(), false, s.getName());

		Collection<TypeReference> interfaces = s.getInterfaceList();
		if (interfaces != null && interfaces.size() > 0) {
			for (TypeReference itf : interfaces)
				checkSuperclass(itf, true, s.getName());
		}
		return true;
	}

	@Override
	public boolean visit(ClassInstanceCreation s) throws Exception {
		if (s.getClassName() instanceof TypeReference) {
			TypeReferenceInfo tri = new TypeReferenceInfo((TypeReference) s.getClassName(), false);
			IType[] types = getAllTypes(tri.getFullyQualifiedName(), sourceModule, s.getClassName().sourceStart());
			for (IType type : types) {
				if (PHPFlags.isTrait(type.getFlags()) || PHPFlags.isInterface(type.getFlags())
						|| PHPFlags.isAbstract(type.getFlags())) {
					reportProblem(s.getClassName(), Messages.CannotInstantiateType,
							PHPProblemIdentifier.CannotInstantiateType, type.getElementName(), ProblemSeverities.Error);
					break;
				}
			}
		}
		return visitGeneral(s);
	}

	@Override
	public boolean visit(InterfaceDeclaration s) throws Exception {
		if (s.getSuperClasses() == null)
			return true;
		for (ASTNode node : s.getSuperClasses().getChilds()) {
			checkSuperclass((TypeReference) node, true, s.getName());
		}
		return true;
	}

	@Override
	public boolean visit(TypeDeclaration s) throws Exception {
		if (!(s instanceof NamespaceDeclaration)) {
			checkDuplicateTypeDeclaration(s);
		}
		return super.visit(s);
	}

	public boolean visit(UsePart part) throws Exception {
		UsePartInfo info = new UsePartInfo(part);
		TypeReferenceInfo tri = info.getTypeReferenceInfo();

		if (tri.getTypeReference() instanceof FullyQualifiedReference) {
			int elementType = ((FullyQualifiedReference) tri.getTypeReference()).getElementType();
			if (elementType == FullyQualifiedReference.T_CONSTANT
					|| elementType == FullyQualifiedReference.T_FUNCTION) {
				// TODO implement later, skip check for function and constant
				// for now
				return false;
			}
		}

		String name = tri.getTypeName();
		String currentNamespaceName;
		if (currentNamespace == null || currentNamespace.isGlobal()) {
			currentNamespaceName = ""; //$NON-NLS-1$
		} else {
			currentNamespaceName = currentNamespace.getName();
		}
		String lcName = info.getRealName().toLowerCase();
		if (!findElement(tri)) {
			info.isProblemReported = true;
			reportProblem(tri.getTypeReference(), Messages.ImportNotFound, PHPProblemIdentifier.ImportNotFound, name,
					ProblemSeverities.Error);
		} else if (usePartInfo.get(lcName) != null) {
			info.isProblemReported = true;
			reportProblem(tri.getTypeReference(), Messages.DuplicateImport, PHPProblemIdentifier.DuplicateImport,
					new String[] { name, info.getRealName() }, ProblemSeverities.Error);
		} else if (!info.isAlias && info.getNamespaceName().equals(currentNamespaceName)) {
			info.isProblemReported = true;
			reportProblem(tri.getTypeReference(), Messages.UnnecessaryImport, PHPProblemIdentifier.UnnecessaryImport,
					new String[] { name }, ProblemSeverities.Warning);
		}
		usePartInfo.put(lcName, info);
		return false;
	}

	@Override
	public boolean visit(TraitUseStatement s) throws Exception {
		List<TypeReference> traits = s.getTraitList();
		for (TypeReference trait : traits) {
			TypeReferenceInfo tri = new TypeReferenceInfo(trait, false);
			IType[] types = getAllTypes(tri.getFullyQualifiedName(), sourceModule, trait.start());
			for (IType type : types) {
				if (!PHPFlags.isTrait(type.getFlags())) {
					reportProblem(trait, Messages.CannotUseTypeAsTrait, PHPProblemIdentifier.CannotUseTypeAsTrait,
							new String[] { trait.getName() }, ProblemSeverities.Error);
					break;
				}
			}
		}
		return true;
	}

	/**
	 * Generic checks to only visit PHPDoc type references whose names are valid
	 * php identifier names. See also
	 * {@link PHPSelectionEngine#lookForMatchingElements()} for more complete
	 * and precise PHPDoc type references handling.
	 */
	@SuppressWarnings("null")
	private void visitPHPDocType(TypeReference typeReference, ProblemSeverity severity) throws Exception {
		if (PHPDOC_TYPE_SKIP.contains(typeReference.getName().toLowerCase())) {
			return;
		}

		String name = typeReference.getName();
		assert name.length() > 0;

		int idx = name.indexOf(PAAMAYIM_NEKUDOTAIM);
		if (idx == -1) {
			// look if complete name is a valid identifier name
			if (PHPTextSequenceUtilities.readIdentifierStartIndex(version, name, name.length(), false) == 0) {
				visit(typeReference, severity, true);
			}
		} else if (idx > 0) {
			// look if name part before "::" is a valid (and non-empty)
			// identifier name
			if (PHPTextSequenceUtilities.readIdentifierStartIndex(version, name, idx, false) == 0) {
				visit(new TypeReference(typeReference.start(), typeReference.start() + idx, name.substring(0, idx)),
						severity, true);
			}
		}
	}

	@Override
	public boolean visit(PHPDocTag phpDocTag) throws Exception {
		for (TypeReference fullTypeReference : phpDocTag.getTypeReferences()) {
			String fullTypeName = fullTypeReference.getName();
			// look for all type names inside fullTypeName by removing all type
			// delimiters, regardless if fullTypeName's content respects PHPDoc
			// standards or not
			Matcher matcher = PHPEvaluationUtils.TYPE_DELIMS_PATTERN.matcher(fullTypeName);
			int start = 0;
			while (matcher.find()) {
				if (matcher.start() != start) {
					String typeName = fullTypeName.substring(start, matcher.start());
					TypeReference typeReference = new TypeReference(fullTypeReference.start() + start,
							fullTypeReference.start() + start + typeName.length(), typeName);
					visitPHPDocType(typeReference, ProblemSeverities.Warning);
				}
				start = matcher.end();
			}
			if (start == 0) {
				visitPHPDocType(fullTypeReference, ProblemSeverities.Warning);
			} else if (start != fullTypeName.length()) {
				String typeName = fullTypeName.substring(start);
				TypeReference typeReference = new TypeReference(fullTypeReference.start() + start,
						fullTypeReference.start() + start + typeName.length(), typeName);
				visitPHPDocType(typeReference, ProblemSeverities.Warning);
			}
		}
		return false;
	}

	private void checkDuplicateTypeDeclaration(TypeDeclaration node) {
		String name = node.getName();
		String currentNamespaceName;
		if (currentNamespace == null || currentNamespace.isGlobal()) {
			currentNamespaceName = ""; //$NON-NLS-1$
		} else {
			currentNamespaceName = currentNamespace.getName();
		}
		boolean isDuplicateWithUse = false;
		UsePartInfo info = usePartInfo.get(name.toLowerCase());
		if (info != null) {
			String fullyQualifiedName = PHPModelUtils.concatFullyQualifiedNames(NamespaceReference.NAMESPACE_DELIMITER,
					currentNamespaceName, name);
			if (!info.getFullyQualifiedName().equals(fullyQualifiedName)) {
				isDuplicateWithUse = true;
			}
		}
		if (isDuplicateWithUse || !typeDeclared.add(name.toLowerCase())) {
			reportProblem(node.getRef(), Messages.DuplicateDeclaration, PHPProblemIdentifier.DuplicateDeclaration, name,
					ProblemSeverities.Error);
		}
	}

	private void checkUnimplementedMethods(Statement statement, ASTNode classNode) throws ModelException {
		checkUnimplementedMethods(statement, classNode.sourceStart(), classNode.sourceEnd());
	}

	private void checkUnimplementedMethods(Statement statement, int nodeStart, int nodeEnd) throws ModelException {
		IModelElement element = sourceModule.getElementAt(statement.start());
		if (!(element instanceof IType))
			return;
		IType type = (IType) element;
		if (type.getSuperClasses().length > 0 && !PHPFlags.isAbstract(type.getFlags())) {
			IMethod[] methods = PHPModelUtils.getUnimplementedMethods(type, null);
			for (IMethod method : methods) {
				if (method.getParent().getElementName().equals(type.getElementName())) {
					continue;
				}
				StringBuilder methodName = new StringBuilder(method.getParent().getElementName()).append("::"); //$NON-NLS-1$
				PHPModelUtils.getMethodLabel(method, methodName);
				String message = Messages.getString("AbstractMethodMustBeImplemented",
						new String[] { type.getElementName(), methodName.toString() });
				reportProblem(nodeStart, nodeEnd, message, PHPProblemIdentifier.AbstractMethodMustBeImplemented,
						ProblemSeverities.Error);
			}
		}
	}

	private void checkSuperclass(TypeReference superClass, boolean isInterface, String className)
			throws ModelException {
		if (superClass != null) {
			TypeReferenceInfo refInfo = new TypeReferenceInfo(superClass, false);
			IType[] types = getAllTypes(refInfo.getFullyQualifiedName(), sourceModule, superClass.sourceStart());
			for (IType type : types) {
				if (!isInterface) {
					if (PHPFlags.isTrait(type.getFlags()) || PHPFlags.isInterface(type.getFlags())) {
						reportProblem(superClass, Messages.SuperclassMustBeAClass,
								PHPProblemIdentifier.SuperclassMustBeAClass,
								new String[] { superClass.getName(), className }, ProblemSeverities.Error);
					}
					if (PHPFlags.isFinal(type.getFlags())) {
						reportProblem(superClass, Messages.ClassExtendFinalClass,
								PHPProblemIdentifier.ClassExtendFinalClass,
								new String[] { className, type.getElementName() }, ProblemSeverities.Error);
					}
				} else {
					if (PHPFlags.isTrait(type.getFlags()) || !PHPFlags.isInterface(type.getFlags())) {
						reportProblem(superClass, Messages.SuperInterfaceMustBeAnInterface,
								PHPProblemIdentifier.SuperInterfaceMustBeAnInterface,
								new String[] { superClass.getName(), className }, ProblemSeverities.Error);
					}
				}
			}
		}
	}

	private void checkUnusedImport() {
		Collection<UsePartInfo> useInfos = usePartInfo.values();
		for (UsePartInfo useInfo : useInfos) {
			if (!useInfo.isProblemReported && useInfo.getRefCount() == 0) {
				FullyQualifiedReference m = useInfo.getUsePart().getNamespace();
				String name = m.getFullyQualifiedName();
				reportProblem(m, Messages.UnusedImport, PHPProblemIdentifier.UnusedImport, name,
						ProblemSeverities.Warning);
			}
		}
		usePartInfo.clear();
		elementExists.clear();
		typeDeclared.clear();
	}

	private boolean findElement(TypeReferenceInfo info) {
		String name = info.getFullyQualifiedName();
		if (elementExists.containsKey(name)) {
			return elementExists.get(name);
		}

		boolean isFound = false;
		try {
			TypeReference type = info.getTypeReference();
			int elementType = FullyQualifiedReference.T_TYPE;
			if (type instanceof FullyQualifiedReference) {
				elementType = ((FullyQualifiedReference) type).getElementType();
			}
			switch (elementType) {
			case FullyQualifiedReference.T_TYPE:
				IModelElement[] types = PHPModelUtils.getTypes(name, sourceModule, type.start(), null);
				if (types.length == 0) {
					types = PHPModelUtils.getTraits(name, sourceModule, type.start(), null, null);
				}
				if (types.length == 0 && info.isUseStatement()) {
					types = sourceModule.codeSelect(type.start(), type.end() - type.start());
				}
				if (types.length > 0) {
					isFound = true;
				}
				break;
			case FullyQualifiedReference.T_CONSTANT:
			case FullyQualifiedReference.T_FUNCTION:
				// TODO implement later, skip check for function and constant
				// for now
				isFound = true;
				break;
			default:
				isFound = false;
				break;
			}
		} catch (ModelException e) {
			PHPCorePlugin.log(e);
		}
		elementExists.put(name, isFound);
		return isFound;
	}

	private void reportProblem(ASTNode s, String message, IProblemIdentifier id, String[] stringArguments,
			ProblemSeverity severity) {
		message = MessageFormat.format(message, (Object[]) stringArguments);
		reportProblem(s, message, id, severity);
	}

	private void reportProblem(ASTNode s, String message, IProblemIdentifier id, ProblemSeverity severity) {
		int start = 0, end = 0;
		if (s != null) {
			start = s.sourceStart();
			end = s.sourceEnd();
		} else {
			start = end = context.getLineTracker().getLineOffset(1);
		}
		reportProblem(start, end, message, id, severity);
	}

	private void reportProblem(ASTNode s, String message, IProblemIdentifier id, String stringArguments,
			ProblemSeverity severity) {
		reportProblem(s, message, id, new String[] { stringArguments }, severity);
	}

	private void reportProblem(int start, int end, String message, IProblemIdentifier id, ProblemSeverity severity) {
		ISourceLineTracker tracker = context.getLineTracker();
		int line = tracker.getLineNumberOfOffset(start);
		IProblem problem = new DefaultProblem(context.getFile().getName(), message, id, null, severity, start, end,
				line, 0);
		context.getProblemReporter().reportProblem(problem);
	}

	private String getFirstSegmentOfTypeName(String typeName) {
		if (typeName != null) {
			String[] segments = typeName.split("\\\\"); //$NON-NLS-1$
			for (String segment : segments) {
				if (segment.trim().length() > 0) {
					return segment;
				}
			}
		}
		return ""; //$NON-NLS-1$
	}

	/**
	 * Returns all types of the given type name(class, interface, trait)
	 * 
	 * @param typeName
	 * @param sourceModule
	 * @param offset
	 * @return
	 */
	private IType[] getAllTypes(String typeName, ISourceModule sourceModule, int offset) {
		try {
			List<IType> lists = new ArrayList<>();
			IType[] types = PHPModelUtils.getTypes(typeName, sourceModule, offset, null);
			lists.addAll(Arrays.asList(types));
			types = PHPModelUtils.getTraits(typeName, sourceModule, offset, null, null);
			lists.addAll(Arrays.asList(types));
			return lists.toArray(new IType[0]);
		} catch (ModelException e) {
			PHPCorePlugin.log(e);
		}
		return new IType[0];
	}

	private class UsePartInfo {
		private UsePart usePart;
		private String realName;
		private int refCount;
		private String fullyQualifiedName;
		private TypeReferenceInfo tri;
		private boolean isAlias = false;
		private boolean isProblemReported = false;

		public UsePartInfo(UsePart usePart) {
			this.usePart = usePart;
			tri = new TypeReferenceInfo(usePart.getNamespace(), true);
			if (usePart.getAlias() != null) {
				realName = usePart.getAlias().getName();
				isAlias = true;
			} else {
				realName = usePart.getNamespace().getName();
			}
			if (tri.getNamespaceName() != null) {
				fullyQualifiedName = tri.getNamespaceName();
			}
			fullyQualifiedName = PHPModelUtils.concatFullyQualifiedNames(fullyQualifiedName,
					usePart.getNamespace().getName());
			if (fullyQualifiedName.length() > 0
					&& fullyQualifiedName.charAt(0) != NamespaceReference.NAMESPACE_SEPARATOR) {
				fullyQualifiedName = NamespaceReference.NAMESPACE_SEPARATOR + fullyQualifiedName;
			}
		}

		public UsePart getUsePart() {
			return usePart;
		}

		public int getRefCount() {
			return refCount;
		}

		public void refer() {
			refCount++;
		}

		public String getRealName() {
			return realName;
		}

		public String getFullyQualifiedName() {
			return fullyQualifiedName;
		}

		public String getNamespaceName() {
			return tri.getNamespaceName();
		}

		public TypeReferenceInfo getTypeReferenceInfo() {
			return tri;
		}

		@Override
		public String toString() {
			String str = "use " + fullyQualifiedName; //$NON-NLS-1$
			if (isAlias) {
				str += " as " + realName; //$NON-NLS-1$
			}
			return str;
		}
	}

	private class TypeReferenceInfo {

		private TypeReference typeReference;
		private boolean isGlobal = false;
		private boolean hasNamespace = false;
		private String namespaceName = ""; //$NON-NLS-1$
		private String typeName;
		private String fullyQualifiedName;
		private boolean isUseStatement;

		public TypeReferenceInfo(TypeReference typeReference, boolean isUseStatement) {
			this.typeReference = typeReference;
			this.isUseStatement = isUseStatement;
			FullyQualifiedReference fullTypeReference = null;
			if (typeReference instanceof FullyQualifiedReference) {
				fullTypeReference = (FullyQualifiedReference) typeReference;
				if (fullTypeReference.getNamespace() != null) {
					if (!fullTypeReference.getNamespace().isLocal()) {
						hasNamespace = true;
						namespaceName = fullTypeReference.getNamespace().getName();
					}
					// for use statement, no need to lookup the use statement
					// to compute namespace name
					if (!isUseStatement) {
						UsePartInfo info = usePartInfo.get(namespaceName.toLowerCase());
						if (info != null) {
							namespaceName = info.getFullyQualifiedName();
						}
					}
				}
			}

			if (fullTypeReference != null && hasNamespace) {
				isGlobal = fullTypeReference.getNamespace().isGlobal();
				typeName = fullTypeReference.getFullyQualifiedName();
			} else {
				typeName = typeReference.getName();
			}

			if (fullTypeReference != null && isGlobal) {
				fullyQualifiedName = fullTypeReference.getFullyQualifiedName();
			} else if (hasNamespace) {
				fullyQualifiedName = PHPModelUtils.concatFullyQualifiedNames(namespaceName, typeReference.getName());
			} else {
				fullyQualifiedName = typeName;
			}
			if (!isUseStatement && !fullyQualifiedName.startsWith(NamespaceReference.NAMESPACE_DELIMITER)) {
				String key = getFirstSegmentOfTypeName(fullyQualifiedName).toLowerCase();
				if (usePartInfo.containsKey(key)) {
					fullyQualifiedName = usePartInfo.get(key).getFullyQualifiedName();
				} else if (currentNamespace != null && !currentNamespace.isGlobal()) {
					fullyQualifiedName = PHPModelUtils.concatFullyQualifiedNames(currentNamespace.getName(),
							fullyQualifiedName);
				}
			}
			if (fullyQualifiedName.length() > 0
					&& fullyQualifiedName.charAt(0) != NamespaceReference.NAMESPACE_SEPARATOR) {
				fullyQualifiedName = NamespaceReference.NAMESPACE_SEPARATOR + fullyQualifiedName;
			}
		}

		public boolean isGlobal() {
			return isGlobal;
		}

		public String getTypeName() {
			return typeName;
		}

		public String getFullyQualifiedName() {
			return fullyQualifiedName;
		}

		public String getNamespaceName() {
			return namespaceName;
		}

		public TypeReference getTypeReference() {
			return typeReference;
		}

		public boolean isUseStatement() {
			return isUseStatement;
		}
	}

}
