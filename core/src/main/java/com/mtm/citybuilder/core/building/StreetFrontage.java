package com.mtm.citybuilder.core.building;

import com.mtm.citybuilder.core.building.rail.Curve;
import com.mtm.citybuilder.core.building.rail.Path;
import com.mtm.citybuilder.core.building.temple.Model;
import com.mtm.citybuilder.core.function.Facility;
import com.mtm.citybuilder.core.plot.TownPlots;
import com.mtm.citybuilder.core.street.StreetClass;
import com.mtm.citybuilder.core.street.StreetPlan;
import com.mtm.citybuilder.core.street.StreetStructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fit a civic building with its forecourt directly against a real street edge. */
final class StreetFrontage {
    @FunctionalInterface interface Land { boolean allows(double x, double z); }
    record Fit(Model model, ModelFootprint footprint, double curbX, double curbZ, double normalX, double normalZ,
               double gap) {}

    private record Front(double x, double z, double tx, double tz, double distance) {}

    static Fit fit(Facility f, TownPlots plots, StreetPlan plan, List<Model> candidates, Land land, Land approachLand, double reach) {
        List<Front> choices = new ArrayList<>();
        for (var street : plan.segments()) {
            if (street.structure() == StreetStructure.BRIDGE || street.structure() == StreetStructure.STAIRS
                    || street.streetClass() == StreetClass.PATH || street.streetClass() == StreetClass.LANE) continue;
            double dx = street.x2() - street.x1(), dz = street.z2() - street.z1(), length = Math.hypot(dx,dz);
            if (length < 1) continue;
            double tx = dx/length, tz = dz/length;
            double near = Math.max(0,Math.min(length,(f.x()-street.x1())*tx + (f.z()-street.z1())*tz));
            if (Math.hypot(street.x1()+near*tx-f.x(),street.z1()+near*tz-f.z()) > reach + street.width()/2) continue;
            for (double along : new double[]{near, length * 0.25, length * 0.75}) {
                double px = street.x1()+along*tx, pz = street.z1()+along*tz;
                double toward = (f.x()-px)*-tz + (f.z()-pz)*tx;
                // The model faces +z, toward the curb; its local +x follows the frontage.
                double sign = toward >= 0 ? -1 : 1;
                double ex = px + sign * tz * street.width()/2, ez = pz - sign * tx * street.width()/2;
                choices.add(new Front(ex,ez,sign*tx,sign*tz,Math.hypot(ex-f.x(),ez-f.z())));
            }
        }
        choices.sort(Comparator.comparingDouble(Front::distance));
        // Town land and rail clearances are expensive; repeated smaller templates share the same columns.
        Map<Long,Boolean> checked = new HashMap<>();
        Land valid = (wx,wz) -> {
            int x=(int)Math.floor(wx), z=(int)Math.floor(wz);
            return checked.computeIfAbsent(((long)x<<32) ^ (z&0xffffffffL), k->land.allows(x+0.5,z+0.5));
        };
        for (Model base : candidates) {
            ModelFootprint mask = ModelFootprint.of(base);
            for (Front front : choices) {
                double nx = -front.tz, nz = front.tx;
                for (int gap : new int[]{2,4,6}) {
                    double mx=front.x-nx*gap, mz=front.z-nz*gap;
                    if (!valid.allows(mx - nx*base.depth()*0.5, mz - nz*base.depth()*0.5)) continue;
                    boolean fits=true;
                    for (int u=0; u<base.width() && fits; u+=3) for (int v=0; v<base.depth() && fits; v+=3) {
                        if (!mask.contains(u,v)) continue;
                        double a=u+0.5-base.width()*0.5, d=v+0.5-base.depth();
                        fits=valid.allows(mx+front.tx*a+nx*d,mz+front.tz*a+nz*d);
                    }
                    if (!fits) continue;
                    Path line=new Path() {
                        public double length(){return base.width();}
                        public double[] point(double s) {
                            double d=s-base.width()*0.5;
                            return new double[]{mx+front.tx*d,mz+front.tz*d,Math.atan2(front.tz,front.tx)};
                        }
                    };
                    Model placed=Curve.along(line,base.maxZ()+1,base.minX()).applyBand(base,null);
                    var occupied=ModelFootprint.of(placed);
                    for (int x=0; x<occupied.width() && fits; x++) for (int z=0; z<occupied.depth() && fits; z++) {
                        if (occupied.contains(x,z)) fits=valid.allows(placed.minX()+x+0.5,placed.minZ()+z+0.5);
                    }
                    if (fits) {
                        // The street-facing doorway may be off-centre (a council wing or parking beside a shop).
                        double[] firstDoor = {Double.NEGATIVE_INFINITY, 0};
                        placed.forEach((x,y,z,state) -> {
                            if (y > 3 || !state.contains("_door") || state.contains("upper") || state.contains("trapdoor")) return;
                            double toward = (x+0.5-mx)*nx + (z+0.5-mz)*nz;
                            double along = (x+0.5-mx)*front.tx + (z+0.5-mz)*front.tz;
                            if (toward > firstDoor[0]) { firstDoor[0]=toward; firstDoor[1]=along; }
                        });
                        double curbX = front.x + firstDoor[1]*front.tx;
                        double curbZ = front.z + firstDoor[1]*front.tz;
                        if (!plots.onStreet(curbX+nx,curbZ+nz)) continue;
                        boolean accessible=true;
                        for (double back=0.5; back<gap && accessible; back+=0.5)
                            for(int offset=-1; offset<=1 && accessible; offset++)
                                accessible=approachLand.allows(curbX-nx*back+front.tx*offset,curbZ-nz*back+front.tz*offset);
                        if (accessible) return new Fit(placed,occupied,curbX,curbZ,nx,nz,gap);
                    }
                }
            }
        }
        return null;
    }
}
