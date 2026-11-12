/** Exercises braces, joining, TABs and array initializers. */
class FormatFixture {
    int run(boolean ready, int[] values) {
        int count = 0;
        if (ready)
            count = Math.max(
                count,
                1);
        else if (values.length == 0) count = 2;
        else count = 3;
        for (int value : values) count += value;
        for (int i = 0; i < 1; i++) count++;
        while (count < 4) count++;
        do count--; while (count > 4);
        int[] offsets = { 1, 2 };
        return count + offsets[0];
    }
}
