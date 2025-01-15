/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2024 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import org.reflections.Reflections;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.command.InteractiveCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ScijavaCommandCheckCapitalLetters {

    private static boolean containsACapitalLetter(String str) {
        char ch;
        for(int i=0;i < str.length();i++) {
            ch = str.charAt(i);
            if (Character.isUpperCase(ch)) {
                return true;
            }
        }
        return false;
    }

    public static void warnAboutCapitalLetter(Class<? extends Command> c) {
        Plugin plugin = c.getAnnotation(Plugin.class);
        if (plugin!=null) {
            List<Field> allFields = new ArrayList<>();
            allFields.addAll(Arrays.asList(filterSkippable(c.getDeclaredFields())));
            allFields.addAll(Arrays.asList(filterSkippable(c.getSuperclass().getDeclaredFields())));

            Field[] fields = allFields.toArray(new Field[0]);

            Arrays.stream(fields).filter(f -> f.isAnnotationPresent(Parameter.class))
                    .forEach(f -> {
                        if (containsACapitalLetter(f.getName())) {
                            System.err.println(c.getName() + ": " + f.getName());
                        }
                    });
        }
    }

    private static Field[] filterSkippable(Field[] declaredFields) {
        return Arrays.stream(declaredFields)
                .filter((f) -> {
                    if (Service.class.isAssignableFrom(f.getType())) {
                        return false;
                    }
                    return !f.getType().equals(Context.class);
                }).toArray(Field[]::new);
    }

    public static void main(String... args) {

        Reflections reflections = new Reflections("ch.epfl.biop");

        Set<Class<? extends Command>> commandClasses =
                reflections.getSubTypesOf(Command.class)
                        .stream()
                        .filter(clazz -> !(InteractiveCommand.class.isAssignableFrom(clazz)))
                        .filter(clazz -> !(DynamicCommand.class.isAssignableFrom(clazz)))
                        .collect(Collectors.toSet());

        commandClasses.forEach(ScijavaCommandCheckCapitalLetters::warnAboutCapitalLetter);

    }

}
