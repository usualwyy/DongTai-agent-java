package com.secnium.iast.core.enhance;

import static org.apache.commons.io.FileUtils.writeByteArrayToFile;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

import com.secnium.iast.core.EngineManager;
import com.secnium.iast.core.PropertyUtils;
import com.secnium.iast.core.enhance.plugins.AbstractClassVisitor;
import com.secnium.iast.core.enhance.plugins.PluginRegister;
import com.secnium.iast.core.enhance.sca.ScaScanner;
import com.secnium.iast.core.report.ErrorLogReport;
import com.secnium.iast.core.util.AsmUtils;
import com.secnium.iast.core.util.matcher.ConfigMatcher;
import com.secnium.iast.log.DongTaiLog;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.time.StopWatch;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class IastClassFileTransformer implements ClassFileTransformer {

    private final IastClassAncestorQuery COMMON_UTILS = IastClassAncestorQuery.getInstance();

    private final boolean isDumpClass;
    private final Instrumentation inst;
    private final String namespace;
    private final PropertyUtils properties;
    private final PluginRegister plugins;


    IastClassFileTransformer(Instrumentation inst) {
        this.inst = inst;
        this.namespace = EngineManager.getNamespace();
        this.isDumpClass = EngineManager.isEnableDumpClass();
        this.properties = PropertyUtils.getInstance();
        this.plugins = new PluginRegister();
    }

    /**
     * 修改字节码
     *
     * @param loader              类加载器
     * @param internalClassName   内部类的名字
     * @param classBeingRedefined 当前类是否是通过 retransform 过来的
     * @param protectionDomain
     * @param srcByteCodeArray    字节码
     * @return 修改后的字节码，为null时，不进行修改
     */
    @Override
    public byte[] transform(final ClassLoader loader,
            final String internalClassName,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] srcByteCodeArray) {
        // if className is null, then, skip
        if (internalClassName == null || internalClassName.startsWith("com/secnium/iast/") || internalClassName
                .startsWith("java/lang/iast/") || internalClassName.startsWith("cn/huoxian/iast/")) {
            return null;
        }
        boolean isRunning = EngineManager.isLingzhiRunning();
        if (isRunning) {
            EngineManager.turnOffLingzhi();
        }

        StopWatch clock = null;
        if (DongTaiLog.isDebugEnabled()) {
            clock = new StopWatch();
            clock.start();
        }

        try {
            if (loader != null && protectionDomain != null) {
                final CodeSource codeSource = protectionDomain.getCodeSource();
                URL location = codeSource.getLocation();
                if (location != null && !internalClassName.startsWith("com/sun/") && !internalClassName
                        .startsWith("sun/") && !location.getFile().isEmpty()
                ) {
                    ScaScanner.scanForSCA(location.getFile(), internalClassName);
                }
            }

            if (null != classBeingRedefined || ConfigMatcher.isHookPoint(internalClassName, loader)) {
                byte[] sourceCodeBak = new byte[srcByteCodeArray.length];
                System.arraycopy(srcByteCodeArray, 0, sourceCodeBak, 0, srcByteCodeArray.length);
                final ClassReader cr = new ClassReader(sourceCodeBak);
                final int flags = cr.getAccess();

                final String[] interfaces = cr.getInterfaces();
                final String superName = cr.getSuperName();
                final String className = cr.getClassName();
                COMMON_UTILS.setLoader(loader);
                COMMON_UTILS.saveAncestors(className, superName, interfaces);
                Set<String> ancestors = COMMON_UTILS.getAncestors(className, superName, interfaces);

                final ClassWriter cw = createClassWriter(loader, cr);
                ClassVisitor cv = plugins.initial(cw, IastContext.build(className, ancestors, interfaces,
                        flags, loader == null, namespace));

                if (cv instanceof AbstractClassVisitor) {
                    cr.accept(cv, ClassReader.EXPAND_FRAMES);
                    AbstractClassVisitor dumpClassVisitor = (AbstractClassVisitor) cv;
                    if (dumpClassVisitor.hasTransformed()) {
                        if (DongTaiLog.isDebugEnabled() && null != clock) {
                            clock.stop();
                            DongTaiLog.debug("conversion class {} is successful, and it takes {}ms.", internalClassName,
                                    clock.getTime());
                        }
                        return dumpClassIfNecessary(cr.getClassName(), cw.toByteArray(), srcByteCodeArray);
                    }
                } else {
                    if (DongTaiLog.isDebugEnabled() && null != clock) {
                        clock.stop();
                        DongTaiLog.debug("failed to convert the class {}, and it takes {} ms", internalClassName,
                                clock.getTime());
                    }
                }
            }
        } catch (
                Throwable cause) {
            ErrorLogReport.sendErrorLog(cause);
        } finally {
            if (isRunning) {
                EngineManager.turnOnLingzhi();
            }
        }

        return null;
    }

    /**
     * 创建ClassWriter for asm
     *
     * @param cr ClassReader
     * @return ClassWriter
     */
    private ClassWriter createClassWriter(final ClassLoader targetClassLoader,
            final ClassReader cr) {
        return new ClassWriter(cr, COMPUTE_FRAMES | COMPUTE_MAXS) {

            /*
             * 注意，为了自动计算帧的大小，有时必须计算两个类共同的父类。
             * 缺省情况下，ClassWriter将会在getCommonSuperClass方法中计算这些，通过在加载这两个类进入虚拟机时，使用反射API来计算。
             * 但是，如果你将要生成的几个类相互之间引用，这将会带来问题，因为引用的类可能还不存在。
             * 在这种情况下，你可以重写getCommonSuperClass方法来解决这个问题。
             *
             * 通过重写 getCommonSuperClass() 方法，更正获取ClassLoader的方式，改成使用指定ClassLoader的方式进行。
             * 规避了原有代码采用Object.class.getClassLoader()的方式
             */
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return AsmUtils.getCommonSuperClass(type1, type2, targetClassLoader);
            }

        };
    }

    /**
     * dump字节码文件到本地，用于DEBUG
     */
    private byte[] dumpClassIfNecessary(String className, byte[] data, byte[] originalData) {
        if (!isDumpClass) {
            return data;
        }
        String javaClassName = className.replace('/', '.');
        String filename;
        String path = properties.getDumpClassPath();
        if (javaClassName.lastIndexOf('.') == -1) {
            filename = javaClassName;
        } else {
            path = path + javaClassName.substring(0, javaClassName.lastIndexOf('.')) + "/";
            filename = javaClassName.substring(javaClassName.lastIndexOf('.') + 1);
        }
        try {
            final File enhancedClass = new File(path + filename + ".class");
            final File originalClass = new File(path + filename + "-original.class");
            final File classPath = new File(enhancedClass.getParent());

            if (!classPath.mkdirs() && !classPath.exists()) {
                DongTaiLog.warn("create dump classpath={} failed.", classPath);
                return data;
            }

            writeByteArrayToFile(enhancedClass, data);
            writeByteArrayToFile(originalClass, originalData);
            if (DongTaiLog.isDebugEnabled()) {
                DongTaiLog.debug("dump class {} to {} success.", className, enhancedClass);
            }
        } catch (IOException e) {
            DongTaiLog.error("dump class {} failed. reason: {}", className, e);
        }

        return data;
    }

    /**
     * 对字节码进行transformer
     *
     * @param inst instrument接口
     */
    public static void init(Instrumentation inst) {
        IastClassFileTransformer iastClassFileTransformer = new IastClassFileTransformer(inst);
        inst.addTransformer(iastClassFileTransformer, true);
        iastClassFileTransformer.retransform();
    }

    /**
     * 卸载字节码的transformer，卸载时，停止
     *
     * @param inst instrument接口
     */
    public static void release(Instrumentation inst) {
        IastClassFileTransformer iastClassFileTransformer = new IastClassFileTransformer(inst);
        inst.removeTransformer(iastClassFileTransformer);
    }

    /**
     * 执行字节码转换
     */
    private void retransform() {
        List<Class<?>> waitingReTransformClasses = IastClassHookPointMatcher.findForRetransform(inst, true);
        final int total = waitingReTransformClasses.size();
        int index = 0;
        for (final Class<?> waitingReTransformClass : waitingReTransformClasses) {
            index++;
            try {
                inst.retransformClasses(waitingReTransformClass);

                if (DongTaiLog.isDebugEnabled()) {
                    DongTaiLog.debug("reTransform class {} success, index={};total={};", waitingReTransformClass,
                            index - 1,
                            total);
                }
            } catch (Throwable t) {
                ErrorLogReport.sendErrorLog(t);
            }
        }
    }

}
