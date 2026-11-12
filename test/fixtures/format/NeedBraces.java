import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.WhileStatement;

/** Adds control-body braces using JDT source ranges when OpenRewrite is unavailable. */
public class NeedBraces
{

	/** Rewrites the supplied files without changing comments or statement order. */
	public static void main(String[] arguments) throws Exception
	{
		for (String argument : arguments)
		{
			Path path = Path.of(argument);
			String source = Files.readString(path, StandardCharsets.UTF_8);
			ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
			Map<String, String> options = JavaCore.getOptions();
			JavaCore.setComplianceOptions(JavaCore.VERSION_11, options);
			parser.setCompilerOptions(options);
			parser.setSource(source.toCharArray());
			CompilationUnit unit = (CompilationUnit) parser.createAST(null);
			for (IProblem problem : unit.getProblems())
			{
				if (problem.isError())
				{
					throw new IllegalArgumentException(path + ":" + problem.getSourceLineNumber() + ": " + problem.getMessage());
				}
			}
			List<Integer> openings = new ArrayList<>();
			List<Integer> closings = new ArrayList<>();
			unit.accept(new ASTVisitor()
			{
				private void wrap(Statement statement)
				{
					if (statement != null && !(statement instanceof Block))
					{
						openings.add(statement.getStartPosition());
						closings.add(statement.getStartPosition() + statement.getLength());
					}
				}

				@Override
				public boolean visit(IfStatement node)
				{
					wrap(node.getThenStatement());
					if (!(node.getElseStatement() instanceof IfStatement))
					{
						wrap(node.getElseStatement());
					}
					return true;
				}

				@Override
				public boolean visit(ForStatement node)
				{
					wrap(node.getBody());
					return true;
				}

				@Override
				public boolean visit(EnhancedForStatement node)
				{
					wrap(node.getBody());
					return true;
				}

				@Override
				public boolean visit(WhileStatement node)
				{
					wrap(node.getBody());
					return true;
				}

				@Override
				public boolean visit(DoStatement node)
				{
					wrap(node.getBody());
					return true;
				}
			});
			// At a shared boundary a closing brace precedes the next opening brace.
			List<Integer> positions = new ArrayList<>();
			positions.addAll(openings);
			positions.addAll(closings);
			positions = positions.stream().distinct().sorted(Comparator.reverseOrder()).toList();
			StringBuilder result = new StringBuilder(source);
			for (int position : positions)
			{
				String insertion = "\n}".repeat((int) closings.stream().filter(p -> p == position).count());
				insertion += "{\n".repeat((int) openings.stream().filter(p -> p == position).count());
				result.insert(position, insertion);
			}
			Files.writeString(path, result, StandardCharsets.UTF_8);
			System.out.println("PROGRESS NeedBraces AST: " + path.getFileName() + ", " + openings.size() + " body/bodies wrapped");
		}
	}
}
