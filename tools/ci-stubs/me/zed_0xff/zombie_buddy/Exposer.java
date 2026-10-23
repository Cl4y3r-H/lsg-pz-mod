package me.zed_0xff.zombie_buddy;
import java.lang.annotation.*;
public class Exposer { @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE) public @interface LuaClass {} }
