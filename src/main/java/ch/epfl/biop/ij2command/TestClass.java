package ch.epfl.biop.ij2command;

import ij.gui.Roi;

import java.util.ArrayList;
import java.util.HashMap;

public class TestClass {

    public void test()  {

        HashMap<Roi, ArrayList<Roi>> map = new HashMap<Roi, ArrayList<Roi>>();

        ArrayList<Roi> roiCells = new ArrayList<>();

        roiCells.stream().filter(r->r.contains(0,0)).findFirst();

    }
}
