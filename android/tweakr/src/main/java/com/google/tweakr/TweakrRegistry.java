// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.tweakr;

import android.support.annotation.NonNull;

import com.google.tweakr.annotations.Tweak;
import com.google.tweakr.collections.FieldMap;
import com.google.tweakr.collections.MethodMap;
import com.google.tweakr.types.DefaultValueTypeConverter;
import com.google.tweakr.types.ValueType;
import com.google.tweakr.types.ValueTypeConverter;
import com.google.tweakr.types.VoidValueType;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import timber.log.Timber;

import static com.google.tweakr.TweakrRepo.FIELD_SEPARATOR;

/**
 * Keeps track of all the Tweaks registered so far and applies changes to each target's Tweaks when
 * they change in the repo.
 */
class TweakrRegistry implements TweakrFirebaseRepo.OnChangeListener {

    private static TweakrRegistry singleton;

    public synchronized static TweakrRegistry get(TweakrRepo repo) {
        if (singleton == null) {
            singleton = new TweakrRegistry(repo);
        }

        return singleton;
    }

    private final TweakrRepo repo;
    private final FieldMap<String> fields = new FieldMap<>();
    private final MethodMap<String> methods = new MethodMap<>();

    private int currentTargetId = 0;
    private ValueTypeConverter typeConverter;

    protected TweakrRegistry(TweakrRepo repo) {
        this.repo = repo;
        typeConverter = new DefaultValueTypeConverter();

        repo.addListener(this);
    }

    public void register(Object target, String namePrefix) {
        Class clazz = target.getClass();
        Timber.d("Registering " + clazz);

        // TODO: store targets in a hash of targetId so you can modify individual ones if necessary???
        currentTargetId++;

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (field.isAnnotationPresent(Tweak.class)) {
                String fieldName = buildName(target, field, namePrefix);

                Tweak annotation = field.getAnnotation(Tweak.class);
                String childName = annotation.child();
                if (childName.length() > 0) {
                    try {
                        Object childTarget = field.get(target);
                        if (childTarget == null) {
                            throw new TweakrException("Child " + childName + " is null. Please initialize your fields before calling Tweakr.register().");
                        }
                        try {
                            Field child = getChildField(childTarget, childName);
                            registerField(childTarget, child, buildName(childTarget, child, fieldName), annotation);
                        } catch (NoSuchFieldException|IllegalAccessException e) {
                            // Field doesn't exist, try a method or property.
                            try {
                                Method child = getChildMethod(childTarget, childName);
                                registerMethod(childTarget, child, buildName(childTarget, child, fieldName), annotation);
                            } catch (NoSuchMethodException ex) {

                                Timber.e(e, "TODO: implement properties! " + childName);
                                // TODO: property
                            }
                        }
                    } catch (IllegalAccessException|TweakrException e) {
                        Timber.e(e, "Failed to get child object " + childName);
                    }
                } else {
                    registerField(target, field, fieldName, annotation);
                }
            }
        }

        Method[] methods = clazz.getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Tweak.class)) {
                registerMethod(target, method, buildName(target, method, namePrefix), method.getAnnotation(Tweak.class));
            }
        }
    }

    private Method getChildMethod(Object target, String childNameRaw) throws NoSuchMethodException {
        // Parse out parameter name for overloading disambiguation.
        String childName = childNameRaw;
        String paramName = null;
        int parenIndex = childNameRaw.indexOf('(');
        if (parenIndex > 0) {
            childName = childNameRaw.substring(0, parenIndex);
            paramName = childNameRaw.substring(parenIndex + 1, childNameRaw.length() - 1);
        }

        Method[] methods = target.getClass().getMethods();
        for (Method method : methods) {
            if ((method.getModifiers() & Modifier.PUBLIC) != 0) {
                if (childName.equals(method.getName())) {
                    if (paramName == null) {
                        return method;
                    } else {
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        if (parameterTypes.length == 1) {
                            if (paramName.equals(parameterTypes[0].getSimpleName())) {
                                return method;
                            }
                        }
                    }
                }
            }
        }

        throw new NoSuchMethodException();
    }

    private Field getChildField(Object target, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        Field field = target.getClass().getField(fieldName);
        if ((field.getModifiers() & Modifier.PUBLIC) == 0) {
            throw new IllegalAccessException();
        }
        return field;
    }

    private void registerField(Object target, Field field, String name, Tweak annotation) {
        fields.add(name, target, field);

        // Register with firebase.
        ValueType valueType = getCustomValueType(annotation);
        if (valueType == null) {
            valueType = typeConverter.getType(field.getType());
        }
        Object curValue = null;
        try {
            curValue = field.get(target);
        } catch (IllegalAccessException e) {
            Timber.e(e, "Failed to get field's current value");
            curValue = valueType.getDefault();
        }
        repo.add(name, currentTargetId, valueType, curValue);
    }

    private ValueType getCustomValueType(Tweak annotation) {
        // ValueType is the attribute default, which means it's not customized.
        if (annotation.valueType() != ValueType.class) {
            try {
                return annotation.valueType().newInstance();
            } catch (IllegalAccessException|InstantiationException e) {
                Timber.e(e, "Could not create custom ValueType");
            }
        }

        return null;
    }

    @NonNull
    private String buildName(Object target, Member field, String targetPrefix) {
        String name = field.getName();
        String targetClass = target.getClass().getSimpleName();
        if (!TextUtils.isEmpty(targetClass)) {
            name = targetClass + FIELD_SEPARATOR + name;
        }
        if (!TextUtils.isEmpty(targetPrefix)) {
            name = targetPrefix + FIELD_SEPARATOR + name;
        }
        return name;
    }

    private void registerMethod(Object target, Method method, String name, Tweak annotation) {
        methods.add(name, target, method);

        // Register with firebase.
        ValueType valueType = getCustomValueType(annotation);
        if (valueType == null) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            valueType = parameterTypes.length > 0 ? typeConverter.getType(parameterTypes[0])
                    : new VoidValueType();
        }

        Object curValue = valueType.getDefault();
        // Get value if property getter exists.
        Method getter = findGetter(target, method);
        if (getter != null) {
            try {
                curValue = getter.invoke(target);
            } catch (ReflectiveOperationException e) {
                Timber.e(e, "Failed to get property's current value");
            }
        }

        repo.add(name, currentTargetId, valueType, curValue);
    }

    private Method findGetter(Object target, Method setter) {
        String setterName = setter.getName();
        if (setterName.startsWith("set")) {
            String propertyName = setterName.substring(3);
            Class<?> targetClass = target.getClass();
            try {
                return targetClass.getMethod("get" + propertyName);
            } catch (NoSuchMethodException e1) {
                try {
                    // Maybe it's a boolean property starting with "is".
                    return targetClass.getMethod("is" + propertyName);
                } catch (NoSuchMethodException e2) {
                    try {
                        return targetClass.getMethod(TextUtils.decapitalize(propertyName));
                    } catch (NoSuchMethodException e3) {
                        Timber.w("Couldn't find getter method for property " + propertyName);
                    }
                }
            }
        }

        return null;
    }

    @Override
    public void onFieldChanged(String name, Object value) {
        try {
            if (fields.has(name)) {
                fields.set(name, value);
            } else if (methods.has(name)) {
                methods.set(name, value);
            } else {
                Timber.w("Could not find field or method " + name);
            }
        } catch (ReflectiveOperationException|IllegalArgumentException e) {
            Timber.e(e, "Failed to set field ");
        }
    }

    public static class TweakrException extends Exception {
        public TweakrException(String s) {
            super(s);
        }
    }
}
