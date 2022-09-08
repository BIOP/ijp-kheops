/*-
 * #%L
 * IJ2 commands that use bio-formats to create pyramidal ome.tiff
 * %%
 * Copyright (C) 2018 - 2022 ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP)
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
package ch.epfl.biop.kheops.command;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.util.function.Consumer;

import ij.IJ;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.FileUtils;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.task.Task;
import org.scijava.task.TaskService;

@Plugin(type = Command.class, menuPath = "Plugins>BIOP>Kheops>Glencoe Optimized Convert File to Pyramidal OME")
public class GlencoeOptimizedConvertCommand implements Command {

    @Parameter(label = "File to convert to ome.tiff", style = "open")
    File input_path;

    @Parameter(label= "Specify an output folder (optional)", style = "directory", required=false, persist=false)
    File output_dir;

    @Parameter(label = "Number of resolution levels")
    Integer nResolutions = 5;

    @Parameter(label = "Compression", choices = {"Uncompressed", "LZW", "JPEG-2000", "JPEG-2000 Lossy","JPEG", "zlib"})
    String compression = "LZW";

    @Parameter
    TaskService taskService;

    public static String bf2rawPath = "bioformats2raw.bat";
    public static String raw2ometiffPath = "raw2ometiff.bat";
    public static Consumer<String> logger = (str) -> IJ.log(str);

    @Override
    public void run() {

        Instant start = Instant.now();
        String fileName = input_path.getName();

        //--------------------

        String fileNameWithOutExt = FilenameUtils.removeExtension(fileName) ;
        File output_path;

        if ((output_dir == null) || (output_dir.toString().equals(""))) {
            File parent_dir = new File(input_path.getParent());
            output_path = new File(parent_dir, fileNameWithOutExt);
        } else {
            output_dir.mkdirs();
            output_path = new File(output_dir, fileNameWithOutExt);
        }

        String tmpdir;

        try {
            Task task = taskService.createTask(input_path.getName()+" conversion to OME-TIFF (Glencoe)");
            task.setStatusMessage("Create temporary folder for raw data");
            tmpdir = Files.createTempDirectory("raw2ometiff").toFile().getAbsolutePath();

            try {

                // Starts the command
                List<String> cmd = new ArrayList<>();

                // bioformats2raw.bat --resolutions=4 source dest
                cmd.add(bf2rawPath);
                cmd.add("--resolutions=" + nResolutions);
                cmd.add(input_path.getAbsolutePath());
                cmd.add(tmpdir + File.separator + "raw");

                logger.accept("Tmp folder = " + tmpdir + File.separator + "raw");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                task.setStatusMessage("- Conversion to raw data (1/2) " + input_path.getAbsolutePath()+"...");
                logger.accept("- Conversion to raw data (1/2) " + input_path.getAbsolutePath()+"...");
                Process p = pb.inheritIO().start();
                p.waitFor();
                logger.accept("- Done");

                // raw2ometiff.bat --compression="LZW" source dest.tiff
                String ometiffFileName = output_path.getAbsolutePath() + ".ome.tiff";

                cmd.clear();
                cmd.add(raw2ometiffPath);
                cmd.add("--compression=" + compression);
                cmd.add("--progress");
                cmd.add(tmpdir + File.separator + "raw");
                cmd.add(ometiffFileName);

                pb = new ProcessBuilder(cmd);
                //pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                //pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                task.setStatusMessage("- Writing as ome.tiff (2/2) : " + ometiffFileName+"...");
                logger.accept("- Writing as ome.tiff (2/2) : " + ometiffFileName);
                p = pb.inheritIO().start();
                p.waitFor();
                logger.accept("- Done");
            } catch (Exception e) {
                logger.accept("Something went wrong, please check the installation instructions at https://c4science.ch/w/bioimaging_and_optics_platform_biop/image-processing/qupath/ome-tiff-conversion/");
                e.printStackTrace();
            } finally {
                task.setStatusMessage("Cleaning raw temp data");
                logger.accept("Cleaning raw temp data");
                FileUtils.deleteDirectory(new File(tmpdir + File.separator + "raw"));
                logger.accept("- Done");
                task.run(() -> {}); // finish task
            }
        } catch (IOException e) {
            logger.accept("Could not create temporary folder");
            e.printStackTrace();
        }

        Instant finish = Instant.now();
        long timeElapsed = Duration.between(start, finish).toMillis();
        logger.accept(input_path.getName()+"\t OME TIFF conversion (Glencoe) \t Run time=\t"+(timeElapsed/1000)+"\t s");
    }
}
