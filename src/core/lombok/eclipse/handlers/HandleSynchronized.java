/*
 * Copyright (C) 2009-2012 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.handlers;

import static lombok.eclipse.handlers.EclipseHandlerUtil.*;

import java.lang.reflect.Modifier;

import lombok.Synchronized;
import lombok.core.AnnotationValues;
import lombok.core.HandlerPriority;
import lombok.core.AST.Kind;
import lombok.eclipse.DeferUntilPostDiet;
import lombok.eclipse.EclipseAnnotationHandler;
import lombok.eclipse.EclipseNode;

import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.ArrayAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedNameReference;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.SynchronizedStatement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.mangosdk.spi.ProviderFor;

/**
 * Handles the {@code lombok.Synchronized} annotation for eclipse.
 */
@ProviderFor(EclipseAnnotationHandler.class)
@DeferUntilPostDiet
@HandlerPriority(value = 1024) // 2^10; @NonNull must have run first, so that we wrap around the statements generated by it.
public class HandleSynchronized extends EclipseAnnotationHandler<Synchronized> {
	private static final char[] INSTANCE_LOCK_NAME = "$lock".toCharArray();
	private static final char[] STATIC_LOCK_NAME = "$LOCK".toCharArray();
	
	@Override public void preHandle(AnnotationValues<Synchronized> annotation, Annotation source, EclipseNode annotationNode) {
		EclipseNode methodNode = annotationNode.up();
		if (methodNode == null || methodNode.getKind() != Kind.METHOD || !(methodNode.get() instanceof MethodDeclaration)) return;
		MethodDeclaration method = (MethodDeclaration)methodNode.get();
		if (method.isAbstract()) return;
		
		createLockField(annotation, annotationNode, method.isStatic(), false);
	}
	
	private char[] createLockField(AnnotationValues<Synchronized> annotation, EclipseNode annotationNode, boolean isStatic, boolean reportErrors) {
		char[] lockName = annotation.getInstance().value().toCharArray();
		Annotation source = (Annotation) annotationNode.get();
		boolean autoMake = false;
		if (lockName.length == 0) {
			autoMake = true;
			lockName = isStatic ? STATIC_LOCK_NAME : INSTANCE_LOCK_NAME;
		}
		
		if (fieldExists(new String(lockName), annotationNode) == MemberExistsResult.NOT_EXISTS) {
			if (!autoMake) {
				if (reportErrors) annotationNode.addError(String.format("The field %s does not exist.", new String(lockName)));
				return null;
			}
			FieldDeclaration fieldDecl = new FieldDeclaration(lockName, 0, -1);
			setGeneratedBy(fieldDecl, source);
			fieldDecl.declarationSourceEnd = -1;
			
			fieldDecl.modifiers = (isStatic ? Modifier.STATIC : 0) | Modifier.FINAL | Modifier.PRIVATE;
			
			//We use 'new Object[0];' because unlike 'new Object();', empty arrays *ARE* serializable!
			ArrayAllocationExpression arrayAlloc = new ArrayAllocationExpression();
			setGeneratedBy(arrayAlloc, source);
			arrayAlloc.dimensions = new Expression[] { makeIntLiteral("0".toCharArray(), source) };
			arrayAlloc.type = new QualifiedTypeReference(TypeConstants.JAVA_LANG_OBJECT, new long[] { 0, 0, 0 });
			setGeneratedBy(arrayAlloc.type, source);
			fieldDecl.type = new QualifiedTypeReference(TypeConstants.JAVA_LANG_OBJECT, new long[] { 0, 0, 0 });
			setGeneratedBy(fieldDecl.type, source);
			fieldDecl.initialization = arrayAlloc;
			// TODO temporary workaround for issue 217. http://code.google.com/p/projectlombok/issues/detail?id=217
			// injectFieldSuppressWarnings(annotationNode.up().up(), fieldDecl);
			injectField(annotationNode.up().up(), fieldDecl);
		}
		
		return lockName;
	}
	
	@Override public void handle(AnnotationValues<Synchronized> annotation, Annotation source, EclipseNode annotationNode) {
		int p1 = source.sourceStart -1;
		int p2 = source.sourceStart -2;
		long pos = (((long)p1) << 32) | p2;
		EclipseNode methodNode = annotationNode.up();
		if (methodNode == null || methodNode.getKind() != Kind.METHOD || !(methodNode.get() instanceof MethodDeclaration)) {
			annotationNode.addError("@Synchronized is legal only on methods.");
			return;
		}
		
		MethodDeclaration method = (MethodDeclaration)methodNode.get();
		if (method.isAbstract()) {
			annotationNode.addError("@Synchronized is legal only on concrete methods.");
			return;
		}
		
		char[] lockName = createLockField(annotation, annotationNode, method.isStatic(), true);
		if (lockName == null) return;
		if (method.statements == null) return;
		
		Block block = new Block(0);
		setGeneratedBy(block, source);
		block.statements = method.statements;
		
		// Positions for in-method generated nodes are special
		block.sourceEnd = method.bodyEnd;
		block.sourceStart = method.bodyStart;
		
		Expression lockVariable;
		if (method.isStatic()) lockVariable = new QualifiedNameReference(new char[][] {
				methodNode.up().getName().toCharArray(), lockName }, new long[] { pos, pos }, p1, p2);
		else {
			lockVariable = new FieldReference(lockName, pos);
			ThisReference thisReference = new ThisReference(p1, p2);
			setGeneratedBy(thisReference, source);
			((FieldReference)lockVariable).receiver = thisReference;
		}
		setGeneratedBy(lockVariable, source);
		
		method.statements = new Statement[] {
				new SynchronizedStatement(lockVariable, block, 0, 0)
		};
		
		// Positions for in-method generated nodes are special
		method.statements[0].sourceEnd = method.bodyEnd;
		method.statements[0].sourceStart = method.bodyStart;
		
		setGeneratedBy(method.statements[0], source);
		
		methodNode.rebuild();
	}
}
