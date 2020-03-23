package com.skyler.cobweb.agent.javassist;

/**
 * Description:
 * <pre>
 *
 * </pre>
 * NB.
 *
 * @author skyler
 * Created by on 2020/3/15 at 9:54 下午
 */

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javassist.*;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.AttributeInfo;
import javassist.bytecode.annotation.Annotation;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

/**
 * 处理指定类的指定方法
 */
public class CombClassFileTransformer implements ClassFileTransformer {

    /**
     * 可逗号分隔，每个index代表不同含义
     */
    private String commandLineArgs;

    public CombClassFileTransformer() {}

    public CombClassFileTransformer(String commandLineArgs) {
        this.commandLineArgs = commandLineArgs;
    }

    private static final String TARGET_DIR = "com/skyler/cobweb/";

    Map<String, String> map = Storage.map();

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {

        if(className.startsWith(TARGET_DIR) && !className.contains("$$EnhancerBySpringCGLIB$$") && !className.contains("$$FastClassBySpringCGLIB$$")){
            ClassPool pool = ClassPool.getDefault();
            //pool.insertClassPath(new LoaderClassPath(loader));

            // 业务目标类
            CtClass cl = null;
            try {
                String realClassName = className.replaceAll("/", ".");
                cl = pool.get(realClassName);
                if(!cl.isAnnotation()
                        && !cl.isInterface()
                        && !cl.isPrimitive()
                        && !cl.isArray()
                        && !cl.isEnum()
                        && TargetAnnotations.hasTargetAnnotation(cl)){

                    CtField[] ctFields = cl.getDeclaredFields();
                    List<CtClass> ctClassOfFields = new ArrayList<>();
                    for (CtField ctField : ctFields) {
                        CtClass ctClassOfField = ctField.getType();
                        if(ctClassOfField.hasAnnotation(TargetAnnotations.FEIGN_CLIENT)) {
                            ctClassOfFields.add(ctClassOfField);
                        }
                    }

                    if(ctClassOfFields != null && !ctClassOfFields.isEmpty()){
                        CtMethod[] methods = cl.getDeclaredMethods();
                        for (CtMethod targetMethod : methods) {
                            if(!targetMethod.isEmpty()) {
                                handlerTargetMethod(targetMethod, ctClassOfFields);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Could not instrument  " + className
                        + ",  exception : " + e.getMessage());
            } finally {
                if (cl != null) {
                    cl.detach();
                }
            }
        }

        return classfileBuffer;
    }

    /**
     * 解析目标方法
     *
     * @param targetMethod
     * @param classOfFields
     * @throws CannotCompileException
     */
    private void handlerTargetMethod(CtMethod targetMethod, List<CtClass> classOfFields) throws CannotCompileException {
        targetMethod.instrument(new ExprEditor(){
            @Override
            public void edit(MethodCall methodCall) throws CannotCompileException {
                for (CtClass classOfField : classOfFields) {
                    String classNameOfField = classOfField.getName();
                    if(classNameOfField.equals(methodCall.getClassName())) {
                        try {
                            handleBodyOfMethod(targetMethod, methodCall, classOfField);
                        } catch (NotFoundException e) {
                            System.out.println("e:" + e);
                            e.printStackTrace();
                        }
                    }
                }
            }
        });
    }

    /**
     * 处理目标方法的body体内容
     *
     * @param targetMethod 目标类的目标方法
     * @param methodCallOfTargetMethodBody 目标方法的方法体内的语句调用
     * @param classOfField 目标类的属性class集合
     * @throws NotFoundException
     */
    private void handleBodyOfMethod(CtMethod targetMethod, MethodCall methodCallOfTargetMethodBody, CtClass classOfField) throws NotFoundException {
        for (String targetRequestAnnotation : TargetAnnotations.getTargetRequestAnnotations()) {
            if(methodCallOfTargetMethodBody.getMethod().hasAnnotation(targetRequestAnnotation)) {
                for (AttributeInfo attribute : methodCallOfTargetMethodBody.getMethod().getMethodInfo().getAttributes()) {
                    if(attribute instanceof AnnotationsAttribute) {
                        AnnotationsAttribute annotationsAttribute = (AnnotationsAttribute)attribute;
                        Annotation annotation = annotationsAttribute.getAnnotation(targetRequestAnnotation);
                        if(Objects.nonNull(annotation)) {
                            String value = annotation.getMemberValue("value").toString();
                            if(null != value && !"".equals(value)) {
                                String toApplication = null;
                                try {
                                    Object[] objects = classOfField.getAnnotations();
                                    for (Object object : objects) {
                                        String s = object.toString();
                                        if(s.contains("FeignClient")) {
                                            toApplication = s;
                                            break;
                                        }
                                    }
                                } catch (ClassNotFoundException e) {
                                    e.printStackTrace();
                                }
                                map.put(classOfField.getName(), value);

                                System.out.print("调用方application: " + commandLineArgs + " 调用类: " +targetMethod.getDeclaringClass().getName()
                                        +" \n调用方方法: " + targetMethod.getLongName()
                                        +" \n被调用方application: " + toApplication + " 被调用方: " + classOfField.getName()
                                        +" \n被调用方方法: " + methodCallOfTargetMethodBody.getClassName() + "." + methodCallOfTargetMethodBody.getMethodName() + " 对应路径: " + value +"\n");

                                System.out.println("-----------------");
                            }
                        }
                    }
                }
            }
        }
    }
}
