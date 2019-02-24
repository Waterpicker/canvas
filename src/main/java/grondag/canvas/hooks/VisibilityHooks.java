package grondag.canvas.hooks;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;

import grondag.canvas.mixinext.ChunkVisibility;
import grondag.canvas.mixinext.VisibilityDataExt;
import grondag.fermion.functions.PrimitiveFunctions.ObjToIntFunction;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.fabricmc.fabric.api.client.model.fabric.ModelHelper;
import net.minecraft.class_852;
import net.minecraft.class_854;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class VisibilityHooks {
    public static final ObjToIntFunction<BlockPos> INDEX_FUNCTION;
    public static final int[] EXTERIOR_INDICES;
    static
    {
        VisibilityDataExt visData = (VisibilityDataExt) new class_852();
        INDEX_FUNCTION = visData.indexFunction();
        EXTERIOR_INDICES = visData.exteriorIndices();
    }
    
    @SuppressWarnings("unchecked")
    public static Set<Direction> getVisibleFacingsExt(Object visData, BlockPos eyePos) {
        if (visData instanceof Set)
            return (Set<Direction>) visData;
        else {
            return ((VisibilityMap) visData).getFaceSet(INDEX_FUNCTION.apply(eyePos));
        }
    }

    public static class_854 computeVisiblityExt(class_852 visDataIn) {
        VisibilityDataExt visData = (VisibilityDataExt) visDataIn;
        class_854 setvisibility = new class_854();

        if (4096 - visData.getEmptyCount() < 256) {
            setvisibility.method_3694(true); // set all visible
            ((ChunkVisibility) setvisibility).setVisibilityData(DirectionSet.ALL);
        } else if (visData.getEmptyCount() == 0) {
            setvisibility.method_3694(false);
            ((ChunkVisibility) setvisibility).setVisibilityData(DirectionSet.NONE);
        } else {
            final BitSet bitSet = visData.bitSet();
            VisibilityMap facingMap = VisibilityMap.claim();

            for (int i : EXTERIOR_INDICES) {
                if (!bitSet.get(i)) {
                    final Pair<Set<Direction>, IntArrayList> floodResult = floodFill(visData, i);
                    final Set<Direction> fillSet = floodResult.getLeft();
                    setvisibility.method_3693(fillSet); // set multiple visible
                    byte setIndex = (byte) DirectionSet.sharedIndex(fillSet);
                    final IntArrayList list = floodResult.getRight();
                    final int limit = list.size();
                    for (int j = 0; j < limit; j++)
                        facingMap.setIndex(list.getInt(j), setIndex);
                }
            }
            ((ChunkVisibility) setvisibility).setVisibilityData(facingMap);
        }

        return setvisibility;
    }

    private static class Helpers {
        final EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        final IntArrayList list = new IntArrayList();
        final IntArrayFIFOQueue queue = new IntArrayFIFOQueue();
    }

    private static final ThreadLocal<Helpers> helpers = new ThreadLocal<Helpers>() {
        @Override
        protected Helpers initialValue() {
            return new Helpers();
        }
    };

    private static Pair<Set<Direction>, IntArrayList> floodFill(VisibilityDataExt visData, int pos) {
        final BitSet bitSet = visData.bitSet();
        final Helpers help = helpers.get();
        Set<Direction> set = help.faces;
        set.clear();

        final IntArrayList list = help.list;
        list.clear();

        final IntArrayFIFOQueue queue = help.queue;
        queue.clear();

        queue.enqueue(pos);
        list.add(pos);

        bitSet.set(pos, true);

        while (!queue.isEmpty()) {
            int i = queue.dequeueInt();
            visData.addExteriorToSet(i, set);

            for (int f = 0; f < 6; f++) {
                final Direction enumfacing = ModelHelper.faceFromIndex(f);

                int j = visData.getNeighborIndex(i, enumfacing);

                if (j >= 0 && !bitSet.get(j)) {
                    bitSet.set(j, true);
                    queue.enqueue(j);
                    list.add(j);
                }
            }
        }

        return Pair.of(DirectionSet.sharedInstance(set), list);
    }
}
