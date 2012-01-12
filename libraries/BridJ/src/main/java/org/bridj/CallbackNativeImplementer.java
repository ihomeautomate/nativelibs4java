package org.bridj;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.io.FileNotFoundException;
import java.util.logging.Level;
import java.util.logging.Logger;

//import org.objectweb.asm.*;
import static org.objectweb.asm.Opcodes.*;

import static org.bridj.util.JNIUtils.*;
import org.bridj.*;
import org.bridj.CRuntime.MethodCallInfoBuilder;
import org.bridj.NativeEntities.Builder;
import org.bridj.ann.Convention;
import org.bridj.util.Pair;
import org.bridj.util.Utils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.signature.SignatureWriter;

//import org.objectweb.asm.attrs.*;
class CallbackNativeImplementer extends ClassLoader implements ClassDefiner {

	Map<Class<? extends CallbackInterface>, Class<?>> implClasses = new HashMap<Class<? extends CallbackInterface>, Class<?>>();
	String implNameSuffix = "_NativeImpl";
	final NativeEntities nativeEntities;
    final CRuntime runtime;
    volatile ClassDefiner classDefiner;
	public CallbackNativeImplementer(NativeEntities nativeEntities, CRuntime runtime) {
		super(Platform.getClassLoader());
		this.nativeEntities = nativeEntities;
        this.runtime = runtime;
	}

    public synchronized ClassDefiner getClassDefiner() {
        if (classDefiner == null) {
            classDefiner = PlatformSupport.getInstance().getClassDefiner(this, this);
        }
        return classDefiner;
    }
    
    
	/**
	 * The class created here is to be used to cast a pointer to a callback
	 * @param callbackType
	 */
	public synchronized <T extends CallbackInterface> Class<? extends T> getCallbackImplType(Class<T> callbackType, NativeLibrary forcedLibrary) {
		Class<?> callbackImplType = implClasses.get(callbackType);
		if (callbackImplType == null) {
			try {
				String callbackTypeName = callbackType.getName().replace('.', '/');
				String callbackTypeImplName = callbackTypeName.replace('$', '_') + implNameSuffix;
				String sourceFile = callbackType.getSimpleName() + implNameSuffix + ".java";
				
				Method callbackMethod = runtime.getUniqueAbstractCallbackMethod(callbackType);
				
				Class<?>[] parameterTypes = callbackMethod.getParameterTypes();
				MethodCallInfo mci = new MethodCallInfo(callbackMethod);
				String methodName = callbackMethod.getName();
				String methodSignature = mci.getJavaSignature();//mci.getASMSignature();
				
				byte[] byteArray = emitBytes(sourceFile, callbackTypeName, callbackTypeImplName, methodName, methodSignature);
				callbackImplType = getClassDefiner().defineClass(callbackTypeImplName.replace('/', '.'), byteArray);
                //Method[] methods = callbackImplType.getDeclaredMethods();
				//Method callbackMethodImpl = callbackImplType.getDeclaredMethod(methodName, parameterTypes);
				//mci.setMethod(callbackMethodImpl);
				//mci.setDeclaringClass(callbackImplType);
				//NativeEntities.Builder builder = new NativeEntities.Builder();
				//builder.addJavaToNativeCallback(mci);
				//nativeEntities.addDefinitions(callbackType, builder);
                implClasses.put(callbackType, callbackImplType);
				runtime.register(callbackImplType, forcedLibrary, null);
			} catch (Exception ex) {
				throw new RuntimeException("Failed to create implementation class for callback type " + callbackType.getName() + " : " + ex, ex);
			}
		}
		return (Class)callbackImplType;
	}
    protected Map<Pair<NativeLibrary, Pair<Convention.Style, List<Type>>>, DynamicFunctionFactory> dynamicCallbacks = new HashMap<Pair<NativeLibrary, Pair<Convention.Style, List<Type>>>, DynamicFunctionFactory>();

    private static volatile long nextDynamicCallbackId = 0;
    private static synchronized long getNextDynamicCallbackId() {
        return nextDynamicCallbackId++;
    }

    public synchronized DynamicFunctionFactory getDynamicCallback(NativeLibrary library, final Convention.Style callingConvention, Type returnType, Type... paramTypes) {
        List<Type> list = new ArrayList<Type>(paramTypes.length + 1);
        list.add(returnType);
        list.addAll(Arrays.asList(paramTypes));
        Pair<Convention.Style, List<Type>> pl = new Pair<Convention.Style, List<Type>>(callingConvention, list);
        Pair<NativeLibrary, Pair<Convention.Style, List<Type>>> key = new Pair<NativeLibrary, Pair<Convention.Style, List<Type>>>(library, pl);
        DynamicFunctionFactory cb = dynamicCallbacks.get(key);
        if (cb == null) {
            try {
                StringBuilder javaSig = new StringBuilder("("), desc = new StringBuilder();
                for (Type paramType : paramTypes) {
                    javaSig.append(getNativeSignature(Utils.getClass(paramType)));
                    desc.append(typeDesc(paramType));
                }
                javaSig.append(")").append(getNativeSignature(Utils.getClass(returnType)));
                desc.append("To").append(typeDesc(returnType)).append("_").append(getNextDynamicCallbackId());

                String callbackTypeImplName = "org/bridj/dyncallbacks/" + desc;
                String methodName = "apply";

                byte[] byteArray = emitBytes("<anonymous>", DynamicFunction.class.getName().replace(".", "/"), callbackTypeImplName, methodName, javaSig.toString());
                Class<? extends DynamicFunction> callbackImplType = (Class)getClassDefiner().defineClass(callbackTypeImplName.replace('/', '.'), byteArray);
                
                Class<?>[] paramClasses = new Class[paramTypes.length];
                for (int i = 0, n = paramTypes.length; i < n; i++)
                    paramClasses[i] = Utils.getClass(paramTypes[i]);
                
                MethodCallInfoBuilder methodCallInfoBuilder = new MethodCallInfoBuilder() {
					public MethodCallInfo apply(Method method) throws FileNotFoundException {
						MethodCallInfo mci = super.apply(method);
						mci.setCallingConvention(callingConvention);
						return mci;
					}
                };
                cb = new DynamicFunctionFactory(callbackImplType, callbackImplType.getMethod(methodName, paramClasses), methodCallInfoBuilder);
                dynamicCallbacks.put(key, cb);

                runtime.register(callbackImplType, null, methodCallInfoBuilder);

            } catch (Throwable th) {
                th.printStackTrace();
                throw new RuntimeException("Failed to create callback for " + list + " : " + th, th);
            }
        }
        return cb;
    }
    static String typeDesc(Type t) {
        if (t instanceof Class) {
            Class c = (Class)t;
            if (c == Pointer.class)
                return "Pointer";
            if (c.isPrimitive()) {
                String s = c.getSimpleName();
                return Character.toUpperCase(s.charAt(0)) + s.substring(1);
            } else if (c.isArray()) {
                return typeDesc(c.getComponentType()) + "Array";
            }
            return c.getName().replace('.', '_');
        } else {
            ParameterizedType p = (ParameterizedType)t;
            StringBuilder b = new StringBuilder(typeDesc(p.getRawType()));
            for (Type pp : p.getActualTypeArguments())
                b.append("_").append(typeDesc(pp));
            return b.toString();
        }
    }
	private byte[] emitBytes(String sourceFile, String callbackTypeName,
			String callbackTypeImplName, String methodName,
			String methodSignature) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(V1_5, ACC_PUBLIC + ACC_SUPER,
				callbackTypeImplName, null,
				callbackTypeName, null);

		cw.visitSource(sourceFile, null);

//		{
//	        AnnotationVisitor av = cw.visitAnnotation(classSig(org.bridj.ann.Runtime.class), true);
//	        av.visit("value", Type.getType(classSig(CRuntime.class)));
//	        av.visitEnd();
//		}
		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
			mv.visitCode();
			Label l0 = new Label();
			mv.visitLabel(l0);
			mv.visitLineNumber(5, l0);
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, callbackTypeName,
					"<init>", "()V");
			mv.visitInsn(RETURN);
			Label l1 = new Label();
			mv.visitLabel(l1);
			mv.visitLocalVariable("this",
					"L" + callbackTypeImplName + ";", null, l0, l1, 0);
			mv.visitMaxs(1, 1);
			mv.visitEnd();
		}
		{
			MethodVisitor mv = cw.visitMethod(ACC_PUBLIC + ACC_NATIVE, methodName, methodSignature, null, null);
			mv.visitEnd();
		}
		cw.visitEnd();
		
		return cw.toByteArray();
	}

    public Class<?> defineClass(String className, byte[] data) throws ClassFormatError {
        return defineClass(className, data, 0, data.length);
    }
}