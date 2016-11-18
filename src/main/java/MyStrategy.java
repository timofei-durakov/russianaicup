import com.sun.javaws.jnl.LibraryDesc;
import model.*;

import java.util.*;

public final class MyStrategy implements Strategy {
    private static final double WAYPOINT_RADIUS = 100.0D;

    private static final double LOW_HP_FACTOR = 0.30D;

    /**
     * Ключевые точки для каждой линии, позволяющие упростить управление перемещением волшебника.
     * <p>
     * Если всё хорошо, двигаемся к следующей точке и атакуем противников.
     * Если осталось мало жизненной энергии, отступаем к предыдущей точке.
     */
    private final Map<LaneType, Point2D[]> waypointsByLane = new EnumMap<>(LaneType.class);

    private Random random;

    private LaneType lane;
    private Point2D[] waypoints;

    private Wizard self;
    private World world;
    private Game game;
    private Move move;
    private double angles[] = getAngles();

    static int ANGLES_FACTOR = 36;
    static double MOVE_RADIUS = 70.0;
    static String NEUTRAL_UNITS = "NEUTRAL_UNITS";
    static String FRIENDLY_WIZARDS = "FRIENDLY_WIZARDS";
    static String ENEMIES = "ENEMIES";
    static String ENEMY_WIZARDS = "ENEMY_WIZARDS";
    static String FRIENDLY_MINIONS = "FRIENDLY_MINIONS";
    static String ENEMY_MINIONS = "ENEMY_MINIONS";
    static String FRIENDLY_BUILDINGS = "FRIENDLY_BUILDINGS";
    static String ENEMY_BUILDINGS = "ENEMY_BUILDINGS";
    static String TREES = "TREES";

    private double[] getAngles() {
        double[] angles = new double[ANGLES_FACTOR];
        double sector = StrictMath.PI * 2.0 / 36.0d;
        for (int i = 0; i < ANGLES_FACTOR; i++) {
            angles[i] = sector * i;
        }
        return angles;
    }


    private List<Point2D> getNextPoints(Point2D point) {
        List<Point2D> points = new ArrayList<>(ANGLES_FACTOR);
        int i = 0;
        for (double angle : angles) {
            double x = StrictMath.cos(angle) * MOVE_RADIUS;
            double y = StrictMath.sin(angle) * MOVE_RADIUS;
            Point2D point2d = new Point2D(point.x + x, point.y + y);
            if (point2d.isValid()) {
                points.add(point2d);
            }
        }
        return points;
    }


    private double[] getSpeedForStrafe(double obstacleAngle) {
        double moveSpeed = 0.0;
        double strafeSpeed = 0.0;
        double moveFactor = StrictMath.cos(obstacleAngle);
        double strafeFactor = StrictMath.sin(obstacleAngle);
        moveSpeed = game.getWizardForwardSpeed() * moveFactor;
        strafeSpeed = game.getWizardStrafeSpeed() * strafeFactor;
        return new double[]{moveSpeed, strafeSpeed};
    }

    private boolean unitOverlapsWithSelf(Point2D point, List<LivingUnit> units) {
        for (LivingUnit unit : units) {
            if (point.getDistanceTo(unit) < (self.getRadius() + unit.getRadius()) * 1.5) {
                return true;
            }
        }
        return false;
    }

    private List<Point2D> checkCollisions(List<Point2D> trackPoints, Map<String, List<LivingUnit>> objectsMap) {
        List<Point2D> result = new ArrayList<>();
        for (Point2D point : trackPoints) {
            boolean hasCollisions = false;
            for (Map.Entry<String, List<LivingUnit>> entry : objectsMap.entrySet()) {
                //Skipping aggregated enemies list
                if (entry.getKey().equals(ENEMIES)) {
                    continue;
                }

                if (unitOverlapsWithSelf(point, entry.getValue())) {
                    hasCollisions = true;
                    break;
                }
            }
            if (!hasCollisions) {
                result.add(point);
            }
        }
        return result;
    }

    private int getWizardDamageForPoint(Point2D point2D, List<LivingUnit> wizards) {
        double staffDamage = game.getStaffDamage();
        double staffRange = game.getWizardCastRange();
        int potentialWizardDamage = 0;
        for (LivingUnit w : wizards) {
            double distance = point2D.getDistanceTo(w);
            if (distance <= staffRange) {
                double angle = w.getAngleTo(point2D.x, point2D.y);
                if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
                    potentialWizardDamage += staffDamage;
                }
            }
        }
        return potentialWizardDamage;
    }

    private int getMinionDamageForPoint(Point2D point2D, List<LivingUnit> minions) {
        double dartDamage = game.getDartDirectDamage();
        double dartRange = game.getFetishBlowdartAttackRange();
        double woodCutterDamage = game.getOrcWoodcutterDamage();
        double woodCutterRange = game.getOrcWoodcutterAttackRange();
        int potentialMinionDamage = 0;
        for (LivingUnit lu : minions) {
            Minion minion = (Minion) lu;
            double distance = point2D.getDistanceTo(minion);
            if (minion.getType() == MinionType.FETISH_BLOWDART) {
                if (distance <= dartRange) {
                    double angle = minion.getAngleTo(point2D.x, point2D.y);
                    if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2.0D) {
                        potentialMinionDamage += dartDamage;
                    }
                }
            } else {
                if (distance <= woodCutterRange) {
                    double angle = minion.getAngleTo(point2D.x, point2D.y);
                    if (StrictMath.abs(angle) < game.getOrcWoodcutterAttackSector() / 2.0D) {
                        potentialMinionDamage += woodCutterDamage;
                    }
                }

            }
        }
        return potentialMinionDamage;
    }

    private int getBuildingDamageForPoint(Point2D point2D, List<LivingUnit> buildings) {
        double towerDamage = game.getGuardianTowerDamage();
        double towerRange = game.getGuardianTowerAttackRange();
        double baseDamage = game.getFactionBaseDamage();
        double baseRange = game.getFactionBaseAttackRange();
        int potentialBuildingDamage = 0;
        for (LivingUnit lu : buildings) {
            Building building = (Building) lu;
            double distance = point2D.getDistanceTo(building);
            if (building.getType() == BuildingType.FACTION_BASE) {
                if (distance <= baseRange) {
                    potentialBuildingDamage += baseDamage;
                }
            } else {
                if (distance <= towerRange) {
                    potentialBuildingDamage += towerDamage;
                }
            }
        }
        return potentialBuildingDamage;
    }


    private void weightFightPoints(List<Point2D> trackPoints, Map<String, List<LivingUnit>> objectsMap,
                                   Point2D nextWp, Point2D prevWp) {
        for (Point2D point2D : trackPoints) {
            for (Map.Entry<String, List<LivingUnit>> objects : objectsMap.entrySet()) {
                String key = objects.getKey();
                List<LivingUnit> units = objects.getValue();
                if (ENEMY_WIZARDS.equals(key)) {
                    point2D.appendToCost(-getWizardDamageForPoint(point2D, units));

                } else if (ENEMY_MINIONS.equals(key)) {
                    point2D.appendToCost(-getMinionDamageForPoint(point2D, units));
                } else if (ENEMY_BUILDINGS.equals(key)) {
                    point2D.appendToCost(-getBuildingDamageForPoint(point2D, units));
                }
            }
            point2D.appendToCost(-(int) point2D.getDistanceTo(prevWp));
        }

    }


    /**
     * Основной метод стратегии, осуществляющий управление волшебником.
     * Вызывается каждый тик для каждого волшебника.
     *
     * @param self  Волшебник, которым данный метод будет осуществлять управление.
     * @param world Текущее состояние мира.
     * @param game  Различные игровые константы.
     * @param move  Результатом работы метода является изменение полей данного объекта.
     */
    @Override
    public void move(Wizard self, World world, Game game, Move move) {
        initializeStrategy(self, game);
        initializeTick(self, world, game, move);
        List<Point2D> trackPoints = getNextPoints(new Point2D(self));
        Map<String, List<LivingUnit>> nearest = getNearest();
        //Filter potential collisions here
        trackPoints = checkCollisions(trackPoints, nearest);
        trackPoints.add(new Point2D(self));

        Point2D nextWP = getNextWaypoint();
        Point2D prevWP = getPreviousWaypoint();


        List<LivingUnit> enemies = nearest.get(ENEMIES);
        LivingUnit closestEnemy = enemies.size() > 0 ? enemies.get(0) : null;

        if (closestEnemy != null && self.getDistanceTo(closestEnemy) < self.getCastRange()) {
            weightFightPoints(trackPoints, nearest, nextWP, prevWP);
            trackPoints.sort(new MoveCostComparator());
            Point2D target = trackPoints.get(0);
            LivingUnit targetEnemy = nearest.get(ENEMIES).get(0);
            move.setTurn(self.getAngleTo(targetEnemy));
            double[] speed = getSpeedForStrafe(self.getAngleTo(target.x, target.y));
            move.setSpeed(speed[0]);
            move.setStrafeSpeed(speed[1]);
            move.setCastAngle(self.getAngleTo(nearest.get(ENEMIES).get(0)));
            move.setAction(ActionType.MAGIC_MISSILE);
            move.setMinCastDistance(targetEnemy.getDistanceTo(self) - targetEnemy.getRadius() + game.getMagicMissileRadius());
        } else {
            for (Point2D point : trackPoints) {
                point.appendToCost(-(int) point.getDistanceTo(nextWP));
            }
            trackPoints.sort(new MoveCostComparator());
            Point2D target = trackPoints.get(0);
            goTo(target);
        }

//        if (distance <= self.getCastRange()) {
//            // Постоянно двигаемся из-стороны в сторону, чтобы по нам было сложнее попасть.
//            // Считаете, что сможете придумать более эффективный алгоритм уклонения? Попробуйте! ;)
//            move.setStrafeSpeed(random.nextBoolean() ? game.getWizardStrafeSpeed() : -game.getWizardStrafeSpeed());
//            move.setSpeed(0);
//            double angle = self.getAngleTo(nearestTarget);
//            // ... то поворачиваемся к цели.
//            move.setTurn(angle);
//            // Если цель перед нами, ...
//            if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
//                // ... то атакуем.
//                move.setAction(ActionType.MAGIC_MISSILE);
//                move.setCastAngle(angle);
//                move.setMinCastDistance(distance - nearestTarget.getRadius() + game.getMagicMissileRadius());
//            }
//            return;
//        }
//
    }

    /**
     * Инциализируем стратегию.
     * <p>
     * Для этих целей обычно можно использовать конструктор, однако в данном случае мы хотим инициализировать генератор
     * случайных чисел значением, полученным от симулятора игры.
     */
    private void initializeStrategy(Wizard self, Game game) {
        if (random == null) {
            random = new Random(game.getRandomSeed());

            double mapSize = game.getMapSize();

            waypointsByLane.put(LaneType.MIDDLE, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    random.nextBoolean()
                            ? new Point2D(600.0D, mapSize - 200.0D)
                            : new Point2D(200.0D, mapSize - 600.0D),
                    new Point2D(800.0D, mapSize - 800.0D),
                    new Point2D(mapSize - 600.0D, 600.0D)
            });

            waypointsByLane.put(LaneType.TOP, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    new Point2D(100.0D, mapSize - 400.0D),
                    new Point2D(200.0D, mapSize - 800.0D),
                    new Point2D(200.0D, mapSize * 0.75D),
                    new Point2D(200.0D, mapSize * 0.5D),
                    new Point2D(200.0D, mapSize * 0.25D),
                    new Point2D(200.0D, 200.0D),
                    new Point2D(mapSize * 0.25D, 200.0D),
                    new Point2D(mapSize * 0.5D, 200.0D),
                    new Point2D(mapSize * 0.75D, 200.0D),
                    new Point2D(mapSize - 200.0D, 200.0D)
            });

            waypointsByLane.put(LaneType.BOTTOM, new Point2D[]{
                    new Point2D(100.0D, mapSize - 100.0D),
                    new Point2D(400.0D, mapSize - 100.0D),
                    new Point2D(800.0D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.25D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.5D, mapSize - 200.0D),
                    new Point2D(mapSize * 0.75D, mapSize - 200.0D),
                    new Point2D(mapSize - 200.0D, mapSize - 200.0D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.75D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.5D),
                    new Point2D(mapSize - 200.0D, mapSize * 0.25D),
                    new Point2D(mapSize - 200.0D, 200.0D)
            });

            switch ((int) self.getId()) {
                case 1:
                case 2:
                case 6:
                case 7:
                    lane = LaneType.TOP;
                    break;
                case 3:
                case 8:
                    lane = LaneType.MIDDLE;
                    break;
                case 4:
                case 5:
                case 9:
                case 10:
                    lane = LaneType.BOTTOM;
                    break;
                default:
            }

            waypoints = waypointsByLane.get(lane);

            // Наша стратегия исходит из предположения, что заданные нами ключевые точки упорядочены по убыванию
            // дальности до последней ключевой точки. Сейчас проверка этого факта отключена, однако вы можете
            // написать свою проверку, если решите изменить координаты ключевых точек.

            /*Point2D lastWaypoint = waypoints[waypoints.length - 1];

            Preconditions.checkState(ArrayUtils.isSorted(waypoints, (waypointA, waypointB) -> Double.compare(
                    waypointB.getDistanceTo(lastWaypoint), waypointA.getDistanceTo(lastWaypoint)
            )));*/
        }
    }

    /**
     * Сохраняем все входные данные в полях класса для упрощения доступа к ним.
     */
    private void initializeTick(Wizard self, World world, Game game, Move move) {
        this.self = self;
        this.world = world;
        this.game = game;
        this.move = move;
    }

    /**
     * Данный метод предполагает, что все ключевые точки на линии упорядочены по уменьшению дистанции до последней
     * ключевой точки. Перебирая их по порядку, находим первую попавшуюся точку, которая находится ближе к последней
     * точке на линии, чем волшебник. Это и будет следующей ключевой точкой.
     * <p>
     * Дополнительно проверяем, не находится ли волшебник достаточно близко к какой-либо из ключевых точек. Если это
     * так, то мы сразу возвращаем следующую ключевую точку.
     */
    private Point2D getNextWaypoint() {
        int lastWaypointIndex = waypoints.length - 1;
        Point2D lastWaypoint = waypoints[lastWaypointIndex];

        for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex + 1];
            }

            if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return lastWaypoint;
    }

    /**
     * Действие данного метода абсолютно идентично действию метода {@code getNextWaypoint}, если перевернуть массив
     * {@code waypoints}.
     */
    private Point2D getPreviousWaypoint() {
        Point2D firstWaypoint = waypoints[0];

        for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex - 1];
            }

            if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return firstWaypoint;
    }

    /**
     * Простейший способ перемещения волшебника.
     */
    private void goTo(Point2D point) {
        double angle = self.getAngleTo(point.getX(), point.getY());

        move.setTurn(angle);

        if (StrictMath.abs(angle) < game.getStaffSector() / 4.0D) {
            move.setSpeed(game.getWizardForwardSpeed());
        }
    }

    /**
     * Находим ближайшую цель для атаки, независимо от её типа и других характеристик.
     */

    public static final class DistanceToUnitComparator implements Comparator<LivingUnit> {
        Wizard self;

        DistanceToUnitComparator(Wizard self) {
            this.self = self;
        }

        @Override
        public int compare(LivingUnit o1, LivingUnit o2) {
            return Double.valueOf(self.getDistanceTo(o1) - self.getDistanceTo(o2)).intValue();
        }
    }

    public static final class MoveCostComparator implements Comparator<Point2D> {

        @Override
        public int compare(Point2D o1, Point2D o2) {
            return Double.valueOf(o2.getCost() - o1.getCost()).intValue();
        }
    }

    private Map<String, List<LivingUnit>> getNearest() {
        Map<String, List<LivingUnit>> nearestMap = new HashMap<>();
        DistanceToUnitComparator cmp = new DistanceToUnitComparator(self);
        //TREES
        List<LivingUnit> trees = new ArrayList<>(Arrays.asList(world.getTrees()));
        trees.sort(cmp);
        nearestMap.put(TREES, trees);

        //WIZARDS
        List<LivingUnit> friendlyWizards = new ArrayList<>();
        List<LivingUnit> enemyWizards = new ArrayList<>();
        for (Wizard w : world.getWizards()) {
            if (w.getId() == self.getId()) {
                continue;
            }
            if (self.getFaction() == w.getFaction()) {
                friendlyWizards.add(w);
            } else {
                enemyWizards.add(w);
            }
        }
        friendlyWizards.sort(cmp);
        enemyWizards.sort(cmp);
        nearestMap.put(FRIENDLY_WIZARDS, friendlyWizards);
        nearestMap.put(ENEMY_WIZARDS, enemyWizards);

        //MINIONS
        List<LivingUnit> friendlyMinions = new ArrayList<>();
        List<LivingUnit> enemyMinions = new ArrayList<>();
        List<LivingUnit> neutralMinions = new ArrayList<>();
        for (Minion m : world.getMinions()) {
            if (self.getFaction() == m.getFaction()) {
                friendlyMinions.add(m);
            } else if (m.getFaction() == Faction.NEUTRAL) {
                neutralMinions.add(m);
            } else {
                enemyMinions.add(m);
            }
        }
        friendlyMinions.sort(cmp);
        neutralMinions.sort(cmp);
        enemyMinions.sort(cmp);
        nearestMap.put(FRIENDLY_MINIONS, friendlyMinions);
        nearestMap.put(ENEMY_MINIONS, enemyMinions);
        nearestMap.put(NEUTRAL_UNITS, neutralMinions);

        //BUILDING
        List<LivingUnit> friendlyBuildings = new ArrayList<>();
        List<LivingUnit> enemyBuildings = new ArrayList<>();

        for (Building b : world.getBuildings()) {
            if (self.getFaction() == b.getFaction()) {
                friendlyBuildings.add(b);
            } else {
                enemyBuildings.add(b);
            }
        }
        friendlyBuildings.sort(cmp);
        enemyBuildings.sort(cmp);
        nearestMap.put(FRIENDLY_BUILDINGS, friendlyBuildings);
        nearestMap.put(ENEMY_BUILDINGS, enemyBuildings);
        List<LivingUnit> enemies = new ArrayList<>();
        //ENEMIES
        enemies.addAll(enemyWizards);
        enemies.addAll(enemyMinions);
        enemies.addAll(enemyBuildings);
        enemies.sort(cmp);
        nearestMap.put(ENEMIES, enemies);
        return nearestMap;
    }

    /**
     * Вспомогательный класс для хранения позиций на карте.
     */
    private static final class Point2D {
        private final double x;
        private final double y;
        private int cost = 0;

        private Point2D(Unit unit) {
            this.x = unit.getX();
            this.y = unit.getY();
        }

        private Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public void appendToCost(int cost) {
            this.cost += cost;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getCost() {
            return cost;
        }

        public boolean isValid() {
            return x >= 0 && x <= 4000d && y >= 0 && y <= 4000d;
        }

        public double getDistanceTo(double x, double y) {
            return StrictMath.hypot(this.x - x, this.y - y);
        }

        public double getDistanceTo(Point2D point) {
            return getDistanceTo(point.x, point.y);
        }

        public double getDistanceTo(Unit unit) {
            return getDistanceTo(unit.getX(), unit.getY());
        }
    }

}
