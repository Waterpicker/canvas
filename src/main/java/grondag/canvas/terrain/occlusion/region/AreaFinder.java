package grondag.canvas.terrain.occlusion.region;

import java.util.Arrays;
import java.util.function.Consumer;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import grondag.canvas.perf.ConcurrentMicroTimer;

public class AreaFinder {
	private static final Area[] AREA;

	public static final int AREA_COUNT;

	private static final Area[] SECTION;

	public static final int SECTION_COUNT;

	private static final ConcurrentMicroTimer timer = new ConcurrentMicroTimer("AreaFinder.find", 10000);

	public Area get(int index) {
		return AREA[index];
	}

	public Area getSection(int sectionIndex) {
		return SECTION[sectionIndex];
	}

	static {
		final IntOpenHashSet areas = new IntOpenHashSet();

		areas.add(AreaUtil.areaKey(0, 0, 15, 15));

		areas.add(AreaUtil.areaKey(1, 0, 15, 15));
		areas.add(AreaUtil.areaKey(0, 0, 14, 15));
		areas.add(AreaUtil.areaKey(0, 1, 15, 15));
		areas.add(AreaUtil.areaKey(0, 0, 15, 14));

		for (int x0 = 0; x0 <= 15; x0++) {
			for (int x1 = x0; x1 <= 15; x1++) {
				for (int y0 = 0; y0 <= 15; y0++) {
					for(int y1 = y0; y1 <= 15; y1++) {
						areas.add(AreaUtil.areaKey(x0, y0, x1, y1));
					}
				}
			}
		}

		AREA_COUNT = areas.size();

		AREA = new Area[AREA_COUNT];

		int i = 0;

		for(final int k : areas) {
			AREA[i++] = new Area(k, 0);
		}

		Arrays.sort(AREA, (a, b) -> {
			final int result = Integer.compare(b.areaSize, a.areaSize);

			// within same area size, prefer more compact rectangles
			return result == 0 ? Integer.compare(a.edgeCount, b.edgeCount) : result;
		});

		// PERF: minor, but sort keys instead array to avoid extra alloc at startup
		for (int j = 0; j < AREA_COUNT; j++) {
			AREA[j] = new Area(AREA[j].areaKey, j);
		}

		final ObjectArrayList<Area> sections = new ObjectArrayList<>();

		for (final Area a : AREA) {
			if ((a.x0 == 0  &&  a.x1 == 15) || (a.y0 == 0  &&  a.y1 == 15)) {
				sections.add(a);
			}
		}

		SECTION_COUNT = sections.size();
		SECTION = sections.toArray(new Area[SECTION_COUNT]);

	}

	final long[] bits = new long[4];

	public final ObjectArrayList<Area> areas =  new ObjectArrayList<>();

	//	[12:21:16] [Canvas Render Thread - 3/INFO] (Canvas) Avg AreaFinder.find duration = 124,536 ns, total duration = 1,245, total runs = 10,000
	//	[12:21:20] [Canvas Render Thread - 4/INFO] (Canvas) Avg AreaFinder.find duration = 128,970 ns, total duration = 1,289, total runs = 10,000
	//	[12:21:32] [Canvas Render Thread - 2/INFO] (Canvas) Avg AreaFinder.find duration = 117,787 ns, total duration = 1,177, total runs = 10,000

	public void find(long[] bitsIn, int sourceIndex, Consumer<Area> consumer) {
		timer.start();

		areas.clear();
		final long[] bits = this.bits;
		System.arraycopy(bitsIn, sourceIndex, bits, 0, 4);

		int bitCount = bitCount(bits[0]) +  bitCount(bits[1]) +  bitCount(bits[2]) +  bitCount(bits[3]);

		if (bitCount == 0) {
			return;
		}

		final long hash = AreaUtil.areaHash(bits);

		final Area[] all = AREA;

		for (int i = 0; i < AREA_COUNT; ++i) {
			final Area r = all[i];

			if (r.matchesHash(hash) && r.isIncludedBySample(bits, 0)) {
				final int l = findLargest(bits);
				if (r.areaSize != AreaUtil.size(l)) {
					System.out.println();
					OcclusionBitPrinter.printShape(bits, 0);
					System.out.println();
					AreaUtil.printArea(r.areaKey);
					System.out.println();
					AreaUtil.printArea(l);
					findLargest(bits);
				}

				consumer.accept(r);
				r.clearBits(bits, 0);
				bitCount -= r.areaSize;

				if (bitCount == 0) {
					break;
				}
			}
		}

		timer.stop();
	}

	private static int bitCount(long bits) {
		return bits == 0 ? 0 : Long.bitCount(bits);
	}

	public void findSections(long[] bitsIn, int sourceIndex, Consumer<Area> consumer) {
		areas.clear();
		final long[] bits = this.bits;
		System.arraycopy(bitsIn, sourceIndex, bits, 0, 4);

		final int bitCount = Long.bitCount(bits[0]) + Long.bitCount(bits[1]) + Long.bitCount(bits[2]) + Long.bitCount(bits[3]);

		if (bitCount == 0) {
			return;
		}

		final long hash = AreaUtil.areaHash(bits);

		for(final Area r : SECTION) {
			if (r.matchesHash(hash) && r.isIncludedBySample(bits, 0)) {
				consumer.accept(r);
			}
		}
	}

	public int findLargest(long[] bitsIn) {
		int bestX0 = 0;
		int bestY0 = 0;
		int bestX1 = -1;
		int bestY1 = -1;
		int bestArea = 0;

		// height of prior rows
		// four bits per position, values 0-15
		long heights = 0;

		for (int y = 0; y < 16; ++y) {
			final int rowBits = (int) ((bitsIn[y >> 2] >> ((y & 3) << 4)) & 0xFFFF);

			if  (rowBits == 0) {
				heights = 0;
				continue;
			}

			//	OcclusionBitPrinter.printSpaced(Strings.padStart(Integer.toBinaryString(rowBits), 16, '0'));

			// track start of runs up to current height
			long stackX15 = 0; // 0-15
			long stackH16 = 0; // 1-16
			int stackSize = 0;

			// height of first column is zero if closed, otherwise 1 + previous row first column height
			int runHeight = (rowBits & 1) == 0 ? 0 : (1 + getVal15(heights, 0));
			int runStart = 0;

			// save height for use by next row, unless at top row
			if (y != 15) heights = setVal15(heights, 0, runHeight);

			// NB: inclusive of 16. The height @ 16 will always be zero, closing off last column
			for (int x = 1; x <= 16; ++x) {
				// height here is 0 if closed, otherwise 1 + height of row below
				final int h = (rowBits & (1 << x)) == 0 ? 0 : (1 + getVal15(heights, x));

				// if higher than last start new run
				if (h > runHeight) {
					// push current run onto stack
					if (runHeight != 0) {
						stackX15 = setVal15(stackX15, stackSize, runStart);
						stackH16 = setVal16(stackH16, stackSize, runHeight);
						++stackSize;
					}

					// new run starts here
					runStart = x;
					runHeight = h;
				} else  {
					// if reduction in height, close out current run and
					// also runs on stack until revert to a sustainable run
					// or the stack is empty

					while (h < runHeight) {
						// check for largest area on current run
						final int a = (x - runStart) * runHeight;

						if (a > bestArea) {
							bestArea = a;
							bestX0 = runStart;
							bestX1 = x - 1;
							bestY0 = y - runHeight + 1;
							bestY1 = y;
						}

						if (stackSize == 0) {
							// if we have an empty stack but non-zero height,
							// then run at current height effectively starts
							// where the just-closed prior run  started
							runHeight = h;
							// NB: no change to run start - continue from prior
						} else { // stackSize > 0
							--stackSize;
							final int stackStart = getVal15(stackX15, stackSize);
							final int stackHeight = getVal16(stackH16, stackSize);

							if (stackHeight == h) {
								// if stack run height is same as current, resume run, leave stack popped
								runHeight = h;
								runStart = stackStart;
							} else if (stackHeight < h) {
								// if stack run height is less new height, leave on the stack
								++stackSize;
								// and new run starts from current position
								runHeight = h;
								// NB: no change to run start - continue from prior
							} else {
								// stack area is higher than new height
								// leave stack popped and loop to close out area on the stack
								runHeight = stackHeight;
								runStart = stackStart;
							}
						}
					}

				}

				// track height of this column but don't overflow on last row/column
				if (y != 15 && x < 16) heights = setVal15(heights, x, h);
			}
		}

		return AreaUtil.areaKey(bestX0, bestY0, bestX1, bestY1);
	}

	private static int getVal16(long packed, int x) {
		return 1 + getVal15(packed, x);
	}

	private static long setVal16(long packed, int x, int val) {
		assert val >= 1;
		assert val <= 16;
		return setVal15(packed, x, val -1);
	}

	private static int getVal15(long packed, int x) {
		return (int) ((packed >>> (x << 2)) & 0xF);
	}

	private static long setVal15(long packed, int x, int val) {
		assert val >= 0;
		assert val <= 15;
		final int shift = x << 2;
		final long mask = 0xFL << shift;

		//TODO: remove
		final long result = (packed & ~mask) | (((long) val) << shift);
		assert getVal15(result, x) == val;

		return (packed & ~mask) | (((long) val) << shift);
	}


	//	private final int[] slices = new int[16];
	//	private final int cache[] = new int[16];
	//	private final IntArrayList stack = new IntArrayList();
	//
	//	public int findLargestOld(long[] bitsIn) {
	//		final int[] slices = this.slices;
	//		final int[] cache = this.cache;
	//
	//		for (int i = 0; i < 16; ++i) {
	//			cache[i] = 0;
	//		}
	//
	//		final long b0 = bitsIn[0];
	//		slices[0] = (int) (b0 & 0xFFFF);
	//		slices[1] = (int) ((b0 >> 16) & 0xFFFF);
	//		slices[2] = (int) ((b0 >> 32) & 0xFFFF);
	//		slices[3] = (int) ((b0 >> 48) & 0xFFFF);
	//
	//		final long b1 = bitsIn[1];
	//		slices[4] = (int) (b1 & 0xFFFF);
	//		slices[5] = (int) ((b1 >> 16) & 0xFFFF);
	//		slices[6] = (int) ((b1 >> 32) & 0xFFFF);
	//		slices[7] = (int) ((b1 >> 48) & 0xFFFF);
	//
	//		final long b2 = bitsIn[2];
	//		slices[8] = (int) (b2 & 0xFFFF);
	//		slices[9] = (int) ((b2 >> 16) & 0xFFFF);
	//		slices[10] = (int) ((b2 >> 32) & 0xFFFF);
	//		slices[11] = (int) ((b2 >> 48) & 0xFFFF);
	//
	//		final long b3 = bitsIn[3];
	//		slices[12] = (int) (b3 & 0xFFFF);
	//		slices[13] = (int) ((b3 >> 16) & 0xFFFF);
	//		slices[14] = (int) ((b3 >> 32) & 0xFFFF);
	//		slices[15] = (int) ((b3 >> 48) & 0xFFFF);
	//
	//		int bestX0 = 0;
	//		int bestY0 = 0;
	//
	//		int bestX1 = -1;
	//		int bestY1 = -1;
	//
	//		int bestArea = 0;
	//
	//		for (int y = 15; y >= 0; --y) {
	//			updateCache(slices[y]);
	//			int width = 0; // Width of widest opened rectangle
	//
	//			for (int x = 0; x < 16; ++x) {
	//				if (cache[x]> width) { // Opening new rectangle(s)?
	//					stack.push(x | (width << 16));
	//					width = cache[x];
	//				} else if (cache[x] < width) { // Closing rectangle(s)?
	//					while (!stack.isEmpty()) {
	//						final int val = stack.popInt();
	//						final int x0 = val & 0xFFFF;
	//						final int w0 = (val >>> 16) & 0xFFFF;
	//
	//						if (width * (x - x0) > bestArea) {
	//							bestX0 = x0;
	//							bestY0 = y;
	//
	//							bestX1 = x - 1;
	//							bestY1 = y + width - 1;
	//							width = w0;
	//
	//							bestArea = (bestX1 - bestX0 + 1) * (bestY1 - bestY0 +1);
	//						}
	//
	//						if (cache[x] >= width) {
	//							break;
	//						}
	//					}
	//
	//					width = cache[x];
	//
	//					if (width != 0) {
	//						// Popped an active "opening"?
	//						stack.push(x | (width << 16));
	//					}
	//				}
	//			}
	//		}
	//
	//		return AreaUtil.areaKey(bestX0, bestY0, bestX1, bestY1);
	//	}

	//	private void updateCache(int slice) {
	//		final int[] cache = this.cache;
	//		for (int x = 0; x < 16; ++x) {
	//			final int mask = 1 << x;
	//			if ((slice & mask) != 0) {
	//				cache[x]++;
	//			} else {
	//				cache[x] = 0;
	//			}
	//		}
	//	}

}