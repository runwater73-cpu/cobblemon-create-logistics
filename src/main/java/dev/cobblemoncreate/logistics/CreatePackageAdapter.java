package dev.cobblemoncreate.logistics;

import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.world.item.ItemStack;

/** Boundary around Create's package representation. No contents are unpacked or rebuilt here. */
public final class CreatePackageAdapter {
    private CreatePackageAdapter() {}

    public static boolean isPackage(ItemStack stack) {
        return !stack.isEmpty() && PackageItem.isPackage(stack);
    }

    /**
     * Returns the one-package canonical copy used by a task. The caller must
     * extract exactly one stack from its source handler before creating a task.
     */
    public static ItemStack canonicalCopy(ItemStack stack) {
        if (!isPackage(stack)) {
            throw new IllegalArgumentException("Only Create PackageItem stacks can enter logistics tasks");
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }
}
