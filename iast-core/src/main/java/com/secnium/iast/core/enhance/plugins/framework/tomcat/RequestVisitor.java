package com.secnium.iast.core.enhance.plugins.framework.tomcat;

import com.secnium.iast.core.enhance.IastContext;
import com.secnium.iast.core.enhance.plugins.AbstractClassVisitor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import com.secnium.iast.log.DongTaiLog;

import java.lang.reflect.Modifier;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class RequestVisitor extends AbstractClassVisitor {

    public RequestVisitor(ClassVisitor classVisitor, IastContext context) {
        super(classVisitor, context);
    }

    @Override
    public boolean hasTransformed() {
        return transformed;
    }

    @Override
    public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

        int bool = (!Modifier.isNative(access) && !Modifier.isAbstract(access)) ? 1 : 0;
        if (1 == bool && "recycle".equals(name)) {
            if (DongTaiLog.isDebugEnabled()) {
                DongTaiLog.debug("Instrumenting Tomcat's ByteChunk recycle() method");
            }

            mv = new RequestAdapter(mv, access, name, desc, context);
            transformed = true;
        }
        return mv;
    }

}
