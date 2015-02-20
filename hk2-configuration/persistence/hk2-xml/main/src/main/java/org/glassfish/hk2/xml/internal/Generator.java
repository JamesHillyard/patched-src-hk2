/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.hk2.xml.internal;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;
import javassist.bytecode.ConstPool;
import javassist.bytecode.MethodInfo;
import javassist.bytecode.annotation.Annotation;
import javassist.bytecode.annotation.BooleanMemberValue;
import javassist.bytecode.annotation.ClassMemberValue;
import javassist.bytecode.annotation.StringMemberValue;

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlID;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import org.glassfish.hk2.utilities.general.GeneralUtilities;
import org.glassfish.hk2.utilities.reflection.ClassReflectionHelper;
import org.glassfish.hk2.utilities.reflection.Logger;
import org.glassfish.hk2.xml.api.annotations.XmlIdentifier;
import org.glassfish.hk2.xml.internal.clazz.AltMethodInformation;
import org.glassfish.hk2.xml.internal.clazz.AnnotationInformation;
import org.glassfish.hk2.xml.internal.clazz.InterfaceInformation;
import org.glassfish.hk2.xml.jaxb.internal.XmlElementImpl;
import org.glassfish.hk2.xml.jaxb.internal.XmlRootElementImpl;
import org.jvnet.hk2.annotations.Contract;

/**
 * @author jwells
 *
 */
public class Generator {
    private final static boolean DEBUG_METHODS = Boolean.parseBoolean(GeneralUtilities.getSystemProperty(
            "org.jvnet.hk2.properties.xmlservice.jaxb.methods", "false"));
    
    private final static String CLASS_ADD_ON_NAME = "_$$_Hk2_Jaxb";
    private final static HashSet<String> DO_NOT_HANDLE_METHODS = new HashSet<String>();
    private final static String JAXB_DEFAULT_STRING = "##default";
    public final static String JAXB_DEFAULT_DEFAULT = "\u0000";
    private final static String NO_CHILD_PACKAGE = "java.";
    
    static {
        DO_NOT_HANDLE_METHODS.add("hashCode");
        DO_NOT_HANDLE_METHODS.add("equals");
        DO_NOT_HANDLE_METHODS.add("toString");
        DO_NOT_HANDLE_METHODS.add("annotationType");
    }
    
    public static void generate(InterfaceInformation convertMe,
            ClassReflectionHelper helper,
            CtClass superClazz,
            ClassPool defaultClassPool) throws Throwable {
        String targetClassName = convertMe.getName() + CLASS_ADD_ON_NAME;
        
        CtClass targetCtClass = defaultClassPool.makeClass(targetClassName);
        ClassFile targetClassFile = targetCtClass.getClassFile();
        targetClassFile.setVersionToJava5();
        ConstPool targetConstPool = targetClassFile.getConstPool();
        
        AnnotationsAttribute ctAnnotations = null;
        for (AnnotationInformation convertMeAnnotation : convertMe.getAnnotations()) {
            if (Contract.class.getName().equals(convertMeAnnotation.annotationType()) ||
                    XmlTransient.class.getName().equals(convertMeAnnotation.annotationType())) {
                // We do NOT want the generated class to be in the set of contracts, so
                // skip this one if it is there.
                // We also DO want our own class to be processed by JAXB even
                // if the interface is not.  This is needed for the Eclipselink
                // Moxy version of JAXB, which does some processing of interfaces
                // we do not want them to do
                continue;
            }
            
            if (ctAnnotations == null) {
                ctAnnotations = new AnnotationsAttribute(targetConstPool, AnnotationsAttribute.visibleTag);
            }
            
            if (XmlRootElement.class.getName().equals(convertMeAnnotation.annotationType())) {
                String rootName = convertXmlRootElementName(convertMeAnnotation, convertMe);
                
                XmlRootElement replacement = new XmlRootElementImpl(convertMeAnnotation.getStringValue("namespace"), rootName);
               
                
                createAnnotationCopy(targetConstPool, replacement, ctAnnotations);
            }
            else {
                createAnnotationCopy(targetConstPool, convertMeAnnotation, ctAnnotations);
            }
        }
        if (ctAnnotations != null) {
            targetClassFile.addAttribute(ctAnnotations);
        }
        
        CtClass originalCtClass = defaultClassPool.get(convertMe.getName());
        
        targetCtClass.setSuperclass(superClazz);
        targetCtClass.addInterface(originalCtClass);
        
        NameInformation xmlNameMap = getXmlNameMap(convertMe);
        HashSet<String> alreadyAddedNaked = new HashSet<String>();
        
        HashMap<InterfaceInformation, String> childTypes = new HashMap<InterfaceInformation, String>();
        
        List<AltMethodInformation> allMethods = convertMe.getMethods();
        if (DEBUG_METHODS) {
            Logger.getLogger().debug("Analyzing " + allMethods.size() + " methods of " + convertMe.getName());
        }
        
        HashSet<String> setters = new HashSet<String>();
        HashMap<String, MethodInformation> getters = new HashMap<String, MethodInformation>();
        for (AltMethodInformation wrapper : allMethods) {
            MethodInformation mi = getMethodInformation(wrapper, xmlNameMap);
            
            if (DEBUG_METHODS) {
                Logger.getLogger().debug("Analyzing method " + mi + " of " + convertMe.getSimpleName());
            }
            
            String name = wrapper.getName();
            
            StringBuffer sb = new StringBuffer("public ");
            
            InterfaceInformation originalRetType = wrapper.getReturnType();
            boolean isVoid;
            if (originalRetType == null || void.class.equals(originalRetType)) {
                sb.append("void ");
                isVoid = true;
            }
            else {
                sb.append(getCompilableClass(originalRetType) + " ");
                isVoid = false;
            }
            
            sb.append(name + "(");
            
            InterfaceInformation childType = null;
            boolean getterOrSetter = false;
            if (MethodType.SETTER.equals(mi.methodType)) {
                getterOrSetter = true;
                setters.add(mi.representedProperty);
                
                childType = mi.baseChildType;
                
                sb.append(getCompilableClass(mi.getterSetterType) + " arg0) { super._setProperty(\"" + mi.representedProperty + "\", arg0); }");
            }
            else if (MethodType.GETTER.equals(mi.methodType)) {
                getterOrSetter = true;
                getters.put(mi.representedProperty, mi);
                
                childType = mi.baseChildType;
                
                String cast = "";
                String superMethodName = "_getProperty";
                if (int.class.equals(mi.getterSetterType)) {
                    superMethodName += "I"; 
                }
                else if (long.class.equals(mi.getterSetterType)) {
                    superMethodName += "J";
                }
                else if (boolean.class.equals(mi.getterSetterType)) {
                    superMethodName += "Z";
                }
                else if (byte.class.equals(mi.getterSetterType)) {
                    superMethodName += "B";
                }
                else if (char.class.equals(mi.getterSetterType)) {
                    superMethodName += "C";
                }
                else if (short.class.equals(mi.getterSetterType)) {
                    superMethodName += "S";
                }
                else if (float.class.equals(mi.getterSetterType)) {
                    superMethodName += "F";
                }
                else if (double.class.equals(mi.getterSetterType)) {
                    superMethodName += "D";
                }
                else {
                    cast = "(" + getCompilableClass(mi.getterSetterType) + ") ";
                }
                
                sb.append(") { return " + cast + "super." + superMethodName + "(\"" + mi.representedProperty + "\"); }");
            }
            else if (MethodType.LOOKUP.equals(mi.methodType)) {
                sb.append("java.lang.String arg0) { return (" + getCompilableClass(originalRetType) +
                        ") super._lookupChild(\"" + mi.representedProperty + "\", arg0); }");
                
            }
            else if (MethodType.ADD.equals(mi.methodType)) {
                List<InterfaceInformation> paramTypes = wrapper.getParameterTypes();
                if (paramTypes.size() == 0) {
                    sb.append(") { super._doAdd(\"" + mi.representedProperty + "\", null, null, -1); }");
                }
                else if (paramTypes.size() == 1) {
                    sb.append(paramTypes.get(0).getName() + " arg0) { super._doAdd(\"" + mi.representedProperty + "\",");
                    
                    if (paramTypes.get(0).isInterface()) {
                        sb.append("arg0, null, -1); }");
                    }
                    else if (String.class.getName().equals(paramTypes.get(0).getName())) {
                        sb.append("null, arg0, -1); }");
                    }
                    else {
                        sb.append("null, null, arg0); }");
                    }
                }
                else {
                    sb.append(paramTypes.get(0).getName() + " arg0, int arg1) { super._doAdd(\"" + mi.representedProperty + "\",");
                    
                    if (paramTypes.get(0).isInterface()) {
                        sb.append("arg0, null, arg1); }");
                    }
                    else {
                        sb.append("null, arg0, arg1); }");
                    }
                }
            }
            else if (MethodType.REMOVE.equals(mi.methodType)) {
                List<InterfaceInformation> paramTypes = wrapper.getParameterTypes();
                String cast = "";
                String function = "super._doRemoveZ(\"";
                if (!boolean.class.getName().equals(originalRetType.getName())) {
                    cast = "(" + getCompilableClass(originalRetType) + ") ";
                    function = "super._doRemove(\"";
                }
                
                if (paramTypes.size() == 0) {
                    sb.append(") { return " + cast + function +
                            mi.representedProperty + "\", null, -1); }");
                }
                else if (String.class.getName().equals(paramTypes.get(0))) {
                    sb.append("java.lang.String arg0) { return " + cast  + function +
                            mi.representedProperty + "\", arg0, -1); }");
                }
                else {
                    sb.append("int arg0) { return " + cast + function +
                            mi.representedProperty + "\", null, arg0); }");
                }
            }
            else if (MethodType.CUSTOM.equals(mi.methodType)) {
                List<InterfaceInformation> paramTypes = wrapper.getParameterTypes();
                
                StringBuffer classSets = new StringBuffer();
                StringBuffer valSets = new StringBuffer();
                
                int lcv = 0;
                for (InterfaceInformation paramType : paramTypes) {
                    if (lcv == 0) {
                        sb.append(getCompilableClass(paramType) + " arg" + lcv);
                    }
                    else {
                        sb.append(", " + getCompilableClass(paramType) + " arg" + lcv);
                    }
                    
                    classSets.append("mParams[" + lcv + "] = " + getCompilableClass(paramType) + ".class;\n");
                    valSets.append("mVars[" + lcv + "] = ");
                    if (int.class.equals(paramType)) {
                        valSets.append("new java.lang.Integer(arg" + lcv + ");\n");
                    }
                    else if (long.class.equals(paramType)) {
                        valSets.append("new java.lang.Long(arg" + lcv + ");\n");
                    }
                    else if (boolean.class.equals(paramType)) {
                        valSets.append("new java.lang.Boolean(arg" + lcv + ");\n");
                    }
                    else if (byte.class.equals(paramType)) {
                        valSets.append("new java.lang.Byte(arg" + lcv + ");\n");
                    }
                    else if (char.class.equals(paramType)) {
                        valSets.append("new java.lang.Character(arg" + lcv + ");\n");
                    }
                    else if (short.class.equals(paramType)) {
                        valSets.append("new java.lang.Short(arg" + lcv + ");\n");
                    }
                    else if (float.class.equals(paramType)) {
                        valSets.append("new java.lang.Float(arg" + lcv + ");\n");
                    }
                    else if (double.class.equals(paramType)) {
                        valSets.append("new java.lang.Double(arg" + lcv + ");\n");
                    }
                    else {
                        valSets.append("arg" + lcv + ";\n");
                    }
                    
                    lcv++;
                }
                
                sb.append(") { Class[] mParams = new Class[" + paramTypes.size() + "];\n");
                sb.append("Object[] mVars = new Object[" + paramTypes.size() + "];\n");
                sb.append(classSets.toString());
                sb.append(valSets.toString());
                
                String cast = "";
                String superMethodName = "_invokeCustomizedMethod";
                if (int.class.equals(originalRetType)) {
                    superMethodName += "I"; 
                }
                else if (long.class.equals(originalRetType)) {
                    superMethodName += "J";
                }
                else if (boolean.class.equals(originalRetType)) {
                    superMethodName += "Z";
                }
                else if (byte.class.equals(originalRetType)) {
                    superMethodName += "B";
                }
                else if (char.class.equals(originalRetType)) {
                    superMethodName += "C";
                }
                else if (short.class.equals(originalRetType)) {
                    superMethodName += "S";
                }
                else if (float.class.equals(originalRetType)) {
                    superMethodName += "F";
                }
                else if (double.class.equals(originalRetType)) {
                    superMethodName += "D";
                }
                else if (!isVoid) {
                    cast = "(" + getCompilableClass(originalRetType) + ") ";
                }
                
                if (!isVoid) {
                    sb.append("return " + cast);
                }
                sb.append("super." + superMethodName + "(\"" + name + "\", mParams, mVars);}");
            }
            
            if (getterOrSetter && 
                    (childType != null) &&
                    !childTypes.containsKey(childType)) {
                childTypes.put(childType, mi.representedProperty);
            }
            
            if (DEBUG_METHODS) {
                // Hidden behind static because of potential expensive toString costs
                Logger.getLogger().debug("Adding method for " + convertMe.getSimpleName() + " with implementation " + sb);
            }
            
            CtMethod addMeCtMethod = CtNewMethod.make(sb.toString(), targetCtClass);
            if (wrapper.isVarArgs()) {
                addMeCtMethod.setModifiers(addMeCtMethod.getModifiers() | Modifier.VARARGS);
            }
            MethodInfo methodInfo = addMeCtMethod.getMethodInfo();
            ConstPool methodConstPool = methodInfo.getConstPool();
           
            ctAnnotations = null;
            for (AnnotationInformation convertMeAnnotation : wrapper.getAnnotations()) {
                if (ctAnnotations == null) {
                    ctAnnotations = new AnnotationsAttribute(methodConstPool, AnnotationsAttribute.visibleTag);
                }
                
                if ((childType != null) && XmlElement.class.getName().equals(convertMeAnnotation.annotationType())) {
                        
                    String translatedClassName = childType.getName() + CLASS_ADD_ON_NAME;
                    java.lang.annotation.Annotation anno = new XmlElementImpl(
                            convertMeAnnotation.getStringValue("name"),
                            convertMeAnnotation.getBooleanValue("nillable"),
                            convertMeAnnotation.getBooleanValue("required"),
                            convertMeAnnotation.getStringValue("namespace"),
                            convertMeAnnotation.getStringValue("defaultValue"),
                            translatedClassName);
                    
                    createAnnotationCopy(methodConstPool, anno, ctAnnotations);
                }
                else {  
                    createAnnotationCopy(methodConstPool, convertMeAnnotation, ctAnnotations);
                }
            }
            
            if (getterOrSetter && childType != null &&
                    xmlNameMap.hasNoXmlElement(mi.representedProperty) &&
                    !alreadyAddedNaked.contains(mi.representedProperty)) {
                alreadyAddedNaked.add(mi.representedProperty);
                if (ctAnnotations == null) {
                    ctAnnotations = new AnnotationsAttribute(methodConstPool, AnnotationsAttribute.visibleTag);
                }
                
                java.lang.annotation.Annotation convertMeAnnotation;
                String translatedClassName = childType.getName() + CLASS_ADD_ON_NAME;
                convertMeAnnotation = new XmlElementImpl(
                        JAXB_DEFAULT_STRING,
                        false,
                        false,
                        JAXB_DEFAULT_STRING,
                        JAXB_DEFAULT_DEFAULT,
                        translatedClassName);
                
                createAnnotationCopy(methodConstPool, convertMeAnnotation, ctAnnotations);
            }
            
            if (ctAnnotations != null) {
                methodInfo.addAttribute(ctAnnotations);
            }
            
            targetCtClass.addMethod(addMeCtMethod);
        }
        
        // Now generate the invisible setters for JAXB
        for (Map.Entry<String, MethodInformation> getterEntry : getters.entrySet()) {
            String getterProperty = getterEntry.getKey();
            MethodInformation mi = getterEntry.getValue();
            
            if (setters.contains(getterProperty)) continue;
            
            String getterName = mi.originalMethod.getName();
            String setterName = Utilities.convertToSetter(getterName);
            
            StringBuffer sb = new StringBuffer("private void " + setterName + "(");
            sb.append(getCompilableClass(mi.getterSetterType) + " arg0) { super._setProperty(\"" + mi.representedProperty + "\", arg0); }");
            
            CtMethod addMeCtMethod = CtNewMethod.make(sb.toString(), targetCtClass);
            targetCtClass.addMethod(addMeCtMethod);
        }
        
        // targetCtClass.toClass(convertMe.getClassLoader(), convertMe.getProtectionDomain());
    }
    
    private static void createAnnotationCopy(ConstPool parent, java.lang.annotation.Annotation javaAnnotation,
            AnnotationsAttribute retVal) throws Throwable {
        throw new AssertionError("not yet handled");
        
    }
    
    private static void createAnnotationCopy(ConstPool parent, AnnotationInformation javaAnnotation,
            AnnotationsAttribute retVal) throws Throwable {
        Annotation annotation = new Annotation(javaAnnotation.annotationType(), parent);
        
        Map<String, Object> annotationValues = javaAnnotation.getAnnotationValues();
        for (Map.Entry<String, Object> entry : annotationValues.entrySet()) {
            String valueName = entry.getKey();
            Object value = entry.getValue();
            
            if (DO_NOT_HANDLE_METHODS.contains(valueName)) continue;
            
            Class<?> javaAnnotationType = value.getClass();
            if (String.class.equals(javaAnnotationType)) {
                annotation.addMemberValue(valueName, new StringMemberValue((String) value, parent));
            }
            else if (Boolean.class.equals(javaAnnotationType)) {
                boolean bvalue = (Boolean) value;
                
                annotation.addMemberValue(valueName, new BooleanMemberValue(bvalue, parent));
            }
            else if (Class.class.equals(javaAnnotationType)) {
                String sValue;
                if (javaAnnotation instanceof XmlElementImpl) {
                    sValue = ((XmlElementImpl) javaAnnotation).getTypeByName();
                }
                else {
                    sValue = ((Class<?>) value).getName();
                }
                
                annotation.addMemberValue(valueName, new ClassMemberValue(sValue, parent));
            }
            else {
                throw new AssertionError("Annotation type " + javaAnnotationType.getName() + " is not yet implemented");
            }
            
        }
        
        retVal.addAnnotation(annotation);
    }
    
    private static NameInformation getXmlNameMap(InterfaceInformation convertMe) {
        Map<String, XmlElementData> xmlNameMap = new HashMap<String, XmlElementData>();
        HashSet<String> unmappedNames = new HashSet<String>();
        
        for (AltMethodInformation originalMethod : convertMe.getMethods()) {
            String setterVariable = isSetter(originalMethod);
            if (setterVariable == null) {
                setterVariable = isGetter(originalMethod);
                if (setterVariable == null) continue;
            }
            
            AnnotationInformation xmlElement = originalMethod.getAnnotation(XmlElement.class.getName());
            if (xmlElement != null) {
                String defaultValue = xmlElement.getStringValue("defaultValue");
                
                if (JAXB_DEFAULT_STRING.equals(xmlElement.getStringValue("name"))) {
                    xmlNameMap.put(setterVariable, new XmlElementData(setterVariable, defaultValue));
                }
                else {
                    xmlNameMap.put(setterVariable, new XmlElementData(xmlElement.getStringValue("name"), defaultValue));
                }
            }
            else {
                AnnotationInformation xmlAttribute = originalMethod.getAnnotation(XmlAttribute.class.getName());
                if (xmlAttribute != null) {
                    if (JAXB_DEFAULT_STRING.equals(xmlAttribute.getStringValue("name"))) {
                        xmlNameMap.put(setterVariable, new XmlElementData(setterVariable, JAXB_DEFAULT_DEFAULT));
                    }
                    else {
                        xmlNameMap.put(setterVariable, new XmlElementData(xmlAttribute.getStringValue("name"), JAXB_DEFAULT_DEFAULT));
                    }
                }
                else {
                    unmappedNames.add(setterVariable);
                }
            }
        }
        
        Set<String> noXmlElementNames = new HashSet<String>();
        for (String unmappedName : unmappedNames) {
            if (!xmlNameMap.containsKey(unmappedName)) {
                noXmlElementNames.add(unmappedName);
            }
        }
        
        return new NameInformation(xmlNameMap, noXmlElementNames);
    }
    
    private static MethodInformation getMethodInformation(AltMethodInformation m, NameInformation xmlNameMap) {
        String setterVariable = isSetter(m);
        String getterVariable = null;
        String lookupVariable = null;
        String addVariable = null;
        String removeVariable = null;
        
        if (setterVariable == null) {
            getterVariable = isGetter(m);
            if (getterVariable == null) {
                lookupVariable = isLookup(m);
                if (lookupVariable == null) {
                    addVariable = isAdd(m);
                    if (addVariable == null) {
                        removeVariable = isRemove(m);
                    }
                }
            }
        }
        
        MethodType methodType;
        InterfaceInformation baseChildType = null;
        InterfaceInformation gsType = null;
        String variable = null;
        boolean isList = false;
        boolean isArray = false;
        if (getterVariable != null) {
            // This is a getter
            methodType = MethodType.GETTER;
            variable = getterVariable;
            
            InterfaceInformation returnType = m.getReturnType();
            gsType = returnType;
            
            if (List.class.equals(returnType)) {
                isList = true;
                InterfaceInformation typeChildType = m.getFirstTypeArgument();
                
                baseChildType = typeChildType;
                if (baseChildType == null) {
                    throw new RuntimeException("Cannot find child type of method " + m);
                }
            }
            else if (returnType.isArray()) {
                InterfaceInformation arrayType = returnType.getComponentType();
                if (arrayType.isInterface()) {
                    isArray = true;
                    baseChildType = arrayType;
                }
            }
            else if (returnType.isInterface() && !returnType.getName().startsWith(NO_CHILD_PACKAGE)) {
                baseChildType = returnType;
            }
        }
        else if (setterVariable != null) {
            // This is a setter
            methodType = MethodType.SETTER;
            variable = setterVariable;
            
            InterfaceInformation setterType = m.getParameterTypes().get(0);
            gsType = setterType;
            
            if (List.class.equals(setterType)) {
                isList = true;
                InterfaceInformation typeChildType = m.getFirstTypeArgumentOfParameter(0);
                
                baseChildType = typeChildType;
                if (baseChildType == null) {
                    throw new RuntimeException("Cannot find child type of method " + m);
                }
            }
            else if (setterType.isArray()) {
                InterfaceInformation arrayType = setterType.getComponentType();
                if (arrayType.isInterface()) {
                    isArray = true;
                    baseChildType = arrayType;
                }
            }
            else if (setterType.isInterface() && !setterType.getName().startsWith(NO_CHILD_PACKAGE)) {
                baseChildType = setterType;
            }
        }
        else if (lookupVariable != null) {
            // This is a lookup
            methodType = MethodType.LOOKUP;
            variable = lookupVariable;
            
            InterfaceInformation lookupType = m.getReturnType();
            gsType = lookupType;
        }
        else if (addVariable != null) {
            // This is an add
            methodType = MethodType.ADD;
            variable = addVariable;
        }
        else if (removeVariable != null) {
            // This is an remove
            methodType = MethodType.REMOVE;
            variable = addVariable;
        }
        else {
            methodType = MethodType.CUSTOM;
        }
        
        String representedProperty = xmlNameMap.getNameMap(variable);
        if (representedProperty == null) representedProperty = variable;
        
        String defaultValue = xmlNameMap.getDefaultNameMap(variable);
        
        boolean key = false;
        if ((m.getAnnotation(XmlID.class.getName()) != null) || (m.getAnnotation(XmlIdentifier.class.getName()) != null)) {
            key = true;
        }
        
        return new MethodInformation(m,
                methodType,
                representedProperty,
                defaultValue,
                baseChildType,
                gsType,
                key,
                isList,
                isArray);
    }
    
    private static enum MethodType {
        GETTER,
        SETTER,
        LOOKUP,
        ADD,
        REMOVE,
        CUSTOM
    }
    
    private static class MethodInformation {
        private final AltMethodInformation originalMethod;
        private final MethodType methodType;
        private final InterfaceInformation getterSetterType;
        private final String representedProperty;
        private final String defaultValue;
        private final InterfaceInformation baseChildType;
        private final boolean key;
        private final boolean isList;
        private final boolean isArray;
        
        private MethodInformation(AltMethodInformation originalMethod,
                MethodType methodType,
                String representedProperty,
                String defaultValue,
                InterfaceInformation baseChildType,
                InterfaceInformation gsType,
                boolean key,
                boolean isList,
                boolean isArray) {
            this.originalMethod = originalMethod;
            this.methodType = methodType;
            this.representedProperty = representedProperty;
            this.defaultValue = defaultValue;
            this.baseChildType = baseChildType;
            this.getterSetterType = gsType;
            this.key = key;
            this.isList = isList;
            this.isArray = isArray;
        }
        
        @Override
        public String toString() {
            return "MethodInformation(name=" + originalMethod.getName() + "," +
              "type=" + methodType + "," +
              "getterType=" + getterSetterType + "," +
              "representedProperty=" + representedProperty + "," +
              "defaultValue=" + defaultValue + "," +
              "baseChildType=" + baseChildType + "," +
              "key=" + key + "," +
              "isList=" + isList + "," +
              "isArray=" + isArray + "," +
              System.identityHashCode(this) + ")";
              
        }
    }
    
    private static class NameInformation {
        private final Map<String, XmlElementData> nameMapping;
        private final Set<String> noXmlElement;
        
        private NameInformation(Map<String, XmlElementData> nameMapping, Set<String> unmappedNames) {
            this.nameMapping = nameMapping;
            this.noXmlElement = unmappedNames;
        }
        
        private String getNameMap(String mapMe) {
            if (mapMe == null) return null;
            if (!nameMapping.containsKey(mapMe)) return mapMe;
            return nameMapping.get(mapMe).name;
        }
        
        private String getDefaultNameMap(String mapMe) {
            if (mapMe == null) return JAXB_DEFAULT_DEFAULT;
            if (!nameMapping.containsKey(mapMe)) return JAXB_DEFAULT_DEFAULT;
            return nameMapping.get(mapMe).defaultValue;
        }
        
        private boolean hasNoXmlElement(String variableName) {
            if (variableName == null) return true;
            return noXmlElement.contains(variableName);
        }
    }
    
    private static class XmlElementData {
        private final String name;
        private final String defaultValue;
        
        private XmlElementData(String name, String defaultValue) {
            this.name = name;
            this.defaultValue = defaultValue;
        }
    }
    
    private static String convertXmlRootElementName(AnnotationInformation root, InterfaceInformation clazz) {
        String rootName = root.getStringValue("name");
        
        if (!"##default".equals(rootName)) return rootName;
        
        String simpleName = clazz.getSimpleName();
        
        char asChars[] = simpleName.toCharArray();
        StringBuffer sb = new StringBuffer();
        
        boolean firstChar = true;
        boolean lastCharWasCapital = false;
        for (char asChar : asChars) {
            if (firstChar) {
                firstChar = false;
                if (Character.isUpperCase(asChar)) {
                    lastCharWasCapital = true;
                    sb.append(Character.toLowerCase(asChar));
                }
                else {
                    lastCharWasCapital = false;
                    sb.append(asChar);
                }
            }
            else {
                if (Character.isUpperCase(asChar)) {
                    if (!lastCharWasCapital) {
                        sb.append('-');
                    }
                    
                    sb.append(Character.toLowerCase(asChar));
                    
                    lastCharWasCapital = true;
                }
                else {
                    sb.append(asChar);
                    
                    lastCharWasCapital = false;
                }
            }
        }
        
        return sb.toString();
    }
    
    private static String isGetter(AltMethodInformation method) {
        String name = method.getName();
        
        if (name.startsWith(JAUtilities.GET)) {
            if (name.length() <= JAUtilities.GET.length()) return null;
            if (method.getParameterTypes().size() != 0) return null;
            if (void.class.getName().equals(method.getReturnType().getName())) return null;
            
            String variableName = name.substring(JAUtilities.GET.length());
            
            return Introspector.decapitalize(variableName);
        }
        
        if (name.startsWith(JAUtilities.IS)) {
            if (name.length() <= JAUtilities.IS.length()) return null;
            if (method.getParameterTypes().size() != 0) return null;
            if (boolean.class.getName().equals(method.getReturnType().getName()) || Boolean.class.getName().equals(method.getReturnType().getName())) {
                String variableName = name.substring(JAUtilities.IS.length());
                
                return Introspector.decapitalize(variableName);
            }
            
            return null;
        }
        
        return null;
    }
    
    private static String isSetter(AltMethodInformation method) {
        String name = method.getName();
        
        if (name.startsWith(JAUtilities.SET)) {
            if (name.length() <= JAUtilities.SET.length()) return null;
            if (method.getParameterTypes().size() != 1) return null;
            if (void.class.getName().equals(method.getReturnType().getName())) {
                String variableName = name.substring(JAUtilities.SET.length());
                
                return Introspector.decapitalize(variableName);
            }
            
            return null;
        }
        
        return null;
    }
    
    private static String isLookup(AltMethodInformation method) {
        String name = method.getName();
        
        if (!name.startsWith(JAUtilities.LOOKUP)) return null;
        
        if (name.length() <= JAUtilities.LOOKUP.length()) return null;
        List<InterfaceInformation> parameterTypes = method.getParameterTypes();
        if (parameterTypes.size() != 1) return null;
        if (!String.class.getName().equals(parameterTypes.get(0).getName())) return null;
            
        if (method.getReturnType() == null || void.class.getName().equals(method.getReturnType().getName())) return null;
            
        String variableName = name.substring(JAUtilities.LOOKUP.length());
                
        return Introspector.decapitalize(variableName);
    }
    
    private static String isAdd(AltMethodInformation method) {
        String name = method.getName();
        
        if (!name.startsWith(JAUtilities.ADD)) return null;
        
        if (name.length() <= JAUtilities.ADD.length()) return null;
        if (!void.class.equals(method.getReturnType())) return null;
        
        String variableName = name.substring(JAUtilities.ADD.length());
        String retVal = Introspector.decapitalize(variableName);
        
        List<InterfaceInformation> parameterTypes = method.getParameterTypes();
        if (parameterTypes.size() > 2) return null;
        
        if (parameterTypes.size() == 0) return retVal;
        
        InterfaceInformation param0 = parameterTypes.get(0);
        InterfaceInformation param1 = null;
        if (parameterTypes.size() == 2) {
            param1 = parameterTypes.get(1);
        }
        
        if (String.class.getName().equals(param0.getName()) ||
                int.class.getName().equals(param0.getName()) ||
                param0.isInterface()) {
            // Yes, this is possibly an add
            if (parameterTypes.size() == 1) {
                // add(int), add(String), add(interface) are legal adds
                return retVal;
            }
            
            if (int.class.getName().equals(param0.getName())) {
                // If int is first there must not be any other parameter
                return null;
            }
            else if (String.class.getName().equals(param0.getName())) {
                // add(String, int) is a legal add
                if (int.class.getName().equals(param1.getName())) return retVal;
            }
            else {
                // add(interface, int) is a legal add
                if (int.class.getName().equals(param1.getName())) return retVal;
            }
        }
        return null;
    }
    
    private static String isRemove(AltMethodInformation method) {
        String name = method.getName();
        
        if (!name.startsWith(JAUtilities.REMOVE)) return null;
        
        if (name.length() <= JAUtilities.REMOVE.length()) return null;
        if (method.getReturnType() == null || void.class.equals(method.getReturnType())) return null;
        
        InterfaceInformation returnType = method.getReturnType();
        if (!boolean.class.getName().equals(returnType.getName()) && !returnType.isInterface()) return null;
        
        String variableName = name.substring(JAUtilities.REMOVE.length());
        String retVal = Introspector.decapitalize(variableName);
        
        List<InterfaceInformation> parameterTypes = method.getParameterTypes();
        if (parameterTypes.size() > 1) return null;
        
        if (parameterTypes.size() == 0) return retVal;
        
        InterfaceInformation param0 = parameterTypes.get(0);
        
        if (String.class.getName().equals(param0.getName()) ||
                int.class.getName().equals(param0.getName())) return retVal;
        return null;
    }
    
    private static String getCompilableClass(InterfaceInformation clazz) {
        int depth = 0;
        while (clazz.isArray()) {
            depth++;
            clazz = clazz.getComponentType();
        }
        
        StringBuffer sb = new StringBuffer(clazz.getName());
        for (int lcv = 0; lcv < depth; lcv++) {
            sb.append("[]");
        }
        
        return sb.toString();
    }

}