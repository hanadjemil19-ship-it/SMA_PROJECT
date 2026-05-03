package com.umbb.sruu.utils;

import com.umbb.sruu.ontology.Location;

/**
 * SRUU - Simple Grid Display
 * Shows agent positions on city grid
 */
public class GridDisplay {

    private static final int GRID_SIZE = 50;

    public static void printGrid(Location ambulance1, Location ambulance2,
                                 Location fireTruck, Location police,
                                 Location sensor1, Location sensor2) {

        System.out.println("\n========== CITY GRID (50x50) ==========");

        char[][] grid = new char[GRID_SIZE][GRID_SIZE];

        // Initialize empty
        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                grid[i][j] = '.';
            }
        }

        // Place agents (use x,y coordinates)
        if (ambulance1 != null) placeAgent(grid, ambulance1, 'A');
        if (ambulance2 != null) placeAgent(grid, ambulance2, 'a');
        if (fireTruck != null) placeAgent(grid, fireTruck, 'F');
        if (police != null) placeAgent(grid, police, 'P');
        if (sensor1 != null) placeAgent(grid, sensor1, 'S');
        if (sensor2 != null) placeAgent(grid, sensor2, 's');

        // Print every 5th row/column for compact display
        System.out.print("   ");
        for (int x = 0; x < GRID_SIZE; x += 5) {
            System.out.printf("%2d ", x);
        }
        System.out.println();

        for (int y = 0; y < GRID_SIZE; y += 5) {
            System.out.printf("%2d ", y);
            for (int x = 0; x < GRID_SIZE; x += 5) {
                System.out.print(grid[x][y] + "  ");
            }
            System.out.println();
        }

        System.out.println("A=Ambulance1 a=Ambulance2 F=FireTruck");
        System.out.println("P=Police S=Sensor1 s=Sensor2 .=Empty");
        System.out.println("=======================================\n");
    }

    private static void placeAgent(char[][] grid, Location loc, char symbol) {
        int x = Math.max(0, Math.min(GRID_SIZE - 1, loc.getX()));
        int y = Math.max(0, Math.min(GRID_SIZE - 1, loc.getY()));
        grid[x][y] = symbol;
    }
}