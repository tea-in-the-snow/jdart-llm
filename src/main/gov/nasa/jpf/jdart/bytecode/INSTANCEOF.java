package gov.nasa.jpf.jdart.bytecode;

import gov.nasa.jpf.vm.ElementInfo;
import gov.nasa.jpf.vm.Instruction;
import gov.nasa.jpf.vm.MJIEnv;
import gov.nasa.jpf.vm.StackFrame;
import gov.nasa.jpf.vm.ThreadInfo;
import gov.nasa.jpf.constraints.api.Expression;
import gov.nasa.jpf.constraints.expressions.InstanceofExpression;
import gov.nasa.jpf.constraints.types.ConcreteType;
import gov.nasa.jpf.constraints.types.Type;
import gov.nasa.jpf.jdart.ConcolicUtil;

public class INSTANCEOF extends gov.nasa.jpf.jvm.bytecode.INSTANCEOF {

  public INSTANCEOF(String typeName) {
    super(typeName);
  }

  @Override
  public Instruction execute(ThreadInfo ti) {
    StackFrame sf = ti.getTopFrame();

    Object opAttr = sf.getOperandAttr();

    if (opAttr == null) {
      System.out.println("**********************************************************");
      System.out.println("Execute not symbolic INSTANCEOF. " + sf.getOperandAttr());
      System.out.println("**********************************************************");
      return super.execute(ti);
    }

    // the condition is symbolic

    System.out.println("**********************************************************");
    System.out.println("Execute symbolic INSTANCEOF. " + opAttr);
    System.out.println(opAttr + " instanceof " + super.getType());

    int objRef = sf.pop();

    boolean isInstance = false;
    if (objRef != MJIEnv.NULL) {
      isInstance = ti.getElementInfo(objRef).instanceOf(super.getType());
    }
    sf.push(isInstance ? 1 : 0);

    Expression<?> symRef = (Expression<?>) opAttr;
    Expression<?> symbExpr = new InstanceofExpression(symRef, super.getType());
    System.out.println("Created symbolic instanceof expression: " + symbExpr.toString());
    
    
    sf.setOperandAttr(symbExpr);

    System.out.println("**********************************************************");

    return getNext(ti);
  }

  // private Type<?> resolveType(String typeSignature) {
  //   // 这里需要实现将 typeName 转换为 JConstraints 的 Type<?>
  //   // 这可能涉及类加载和类型映射
  //   // 例如，可以使用 Class.forName 加载类，然后创建 ConcreteType
  //   try {
  //     Class<?> cls = Class.forName(typeSignature.replace('/', '.'));
  //     return new ConcreteType<>(cls);
  //   } catch (ClassNotFoundException e) {
  //     throw new RuntimeException("Failed to resolve type: " + typeName, e);
  //   }
  // }


}
