package com.secnium.iast.core.threadlocalpool;

/**
 * @author dongzhiyong@huoxian.cn
 */
public class BooleanThreadLocal extends ThreadLocal<Boolean> {

    boolean defaultValue;

    public BooleanThreadLocal(Boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    protected Boolean initialValue() {
        return null;
    }

    public void enterEntry() {
        this.set(true);
    }

    public boolean isEnterEntry() {
        Boolean status = this.get();
        return status != null && status;
    }
}
