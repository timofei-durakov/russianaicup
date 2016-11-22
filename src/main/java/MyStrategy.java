import model.*;

import java.util.*;

public final class MyStrategy implements Strategy {
    private static final double WAYPOINT_RADIUS = 200.0D;

    private static final double LOW_HP_FACTOR = 0.40D;

    /**
     * Ключевые точки для каждой линии, позволяющие упростить управление перемещением волшебника.
     * <p/>
     * Если всё хорошо, двигаемся к следующей точке и атакуем противников.
     * Если осталось мало жизненной энергии, отступаем к предыдущей точке.
     */
    private final Map<LaneType, Point2D[]> waypointsByLane = new EnumMap<>(LaneType.class);

    private Random random;

    private LaneType lane;
    private Point2D[] waypoints;
    private double previousHp;
    private Wizard self;
    private World world;
    private Game game;
    private Move move;
    private double angles[] = getAngles();
    private DamageCostComparator damageCostComparator = new DamageCostComparator();
    private MoveCostComparator moveCostComparator = new MoveCostComparator();
    private AttackInterestCostComparator attackInterestCostComparator = new AttackInterestCostComparator();

    static int ANGLES_FACTOR = 36;
    static double MOVE_RADIUS = 70.0;
    static double COLLISION_RADIUS = 35.0;
    static double ATTACK_RADIUS = 4.0;
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

    private Point2D getPotentialPositionForUnit(LivingUnit unit) {
        Point2D result = null;
        double speedX = unit.getSpeedX();
        double speedY = unit.getSpeedY();
        if (speedX != 0.0 || speedY != 0.0) {
            result = new Point2D(unit.getX() + speedX, unit.getY() + speedY);
        }
        return result;
    }

    private List<Point2D> getNextPoints(Point2D point, double radius) {
        List<Point2D> points = new ArrayList<>(ANGLES_FACTOR);
        int i = 0;
        for (double angle : angles) {
            double x = StrictMath.cos(angle) * radius;
            double y = StrictMath.sin(angle) * radius;
            Point2D point2d = new Point2D(point.x + x, point.y + y);
            if (point2d.isValid()) {
                points.add(point2d);
            } else {
                points.add(null);
            }
        }
        return points;
    }

    private List<Point2D> getNextMovePoints(Point2D point2D) {
        return getNextPoints(point2D, MOVE_RADIUS);
    }

    private List<Point2D> getNextAttackPoints(Point2D point2D) {
        return getNextPoints(point2D, ATTACK_RADIUS);
    }
    private List<Point2D> getNextCollisionPoints(Point2D point2D) {
        return getNextPoints(point2D, COLLISION_RADIUS);
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
            if (point.getDistanceTo(unit) < (self.getRadius() + unit.getRadius()) + 10) {
                return true;
            }
        }
        return false;
    }

    private double getWizardDamageForPoint(Point2D point2D, List<LivingUnit> wizards, boolean potentialDanger) {
        double staffDamage = game.getStaffDamage();
        double staffRange = game.getWizardCastRange();
        double dangerRatio = potentialDanger ? 1.3 : 1.0;
        double attackRadius = (staffRange + self.getRadius()) * dangerRatio;
        double attackSector = potentialDanger ? game.getStaffSector() * 10 : game.getStaffSector();
        double potentialWizardDamage = 0.0;
        for (LivingUnit w : wizards) {

            double distance = point2D.getDistanceTo(w);
            if (distance > attackRadius * 2.0) {
                continue;
            }
            Point2D potentialPosition = getPotentialPositionForUnit(w);
            double potentialPositionDistance = Double.MAX_VALUE;
            if (potentialPosition != null) {
                potentialPositionDistance = point2D.getDistanceTo(potentialPosition);
            }
            double angle = w.getAngleTo(point2D.x, point2D.y);
            if (distance <= attackRadius || potentialPositionDistance <= attackRadius) {
                if (StrictMath.abs(angle) < game.getStaffSector() / 2.0 ||
                        StrictMath.abs(angle) - game.getWizardMaxTurnAngle() < attackSector) {
                    potentialWizardDamage += staffDamage;
                }
            }
        }
        return potentialWizardDamage;
    }

    private double getMinionDamageForPoint(Point2D point2D, List<LivingUnit> minions, boolean potentialDanger) {
        double dartDamage = game.getDartDirectDamage();
        double dartRange = game.getFetishBlowdartAttackRange();
        double dartSector = potentialDanger ? game.getFetishBlowdartAttackSector() * 10 :
                game.getFetishBlowdartAttackSector();
        double dangerRatio = potentialDanger ? 1.3 : 1.0;
        double dartAttackRadius = dartRange * dangerRatio;
        double woodCutterDamage = game.getOrcWoodcutterDamage();
        double woodCutterRange = game.getOrcWoodcutterAttackRange();
        double woodCutterAttackRadius = (woodCutterRange + 200) * dangerRatio;
        double woodCutterSector = potentialDanger ? game.getOrcWoodcutterAttackSector() * 10 :
                game.getOrcWoodcutterAttackSector();
        double potentialMinionDamage = 0.0;
        for (LivingUnit lu : minions) {
            Minion minion = (Minion) lu;
            Point2D potentialPosition = getPotentialPositionForUnit(lu);
            double distance = point2D.getDistanceTo(minion);
            double potentialPositionDistance = Double.MAX_VALUE;
            if (potentialPosition != null) {
                potentialPositionDistance = point2D.getDistanceTo(potentialPosition);
            }
            double angle = minion.getAngleTo(point2D.x, point2D.y);
            if (minion.getType() == MinionType.FETISH_BLOWDART) {
                if (distance <= dartAttackRadius || potentialPositionDistance <= dartAttackRadius) {
                    if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2.0D ||
                            StrictMath.abs(angle) - game.getFetishBlowdartAttackSector() < dartSector) {
                        potentialMinionDamage += dartDamage;
                    }
                }
            } else {
                if (distance <= woodCutterAttackRadius || potentialPositionDistance <= woodCutterAttackRadius) {
                    if (StrictMath.abs(angle) < game.getOrcWoodcutterAttackSector() / 2.0D ||
                            StrictMath.abs(angle) - game.getOrcWoodcutterAttackSector() < woodCutterSector) {
                        potentialMinionDamage += woodCutterDamage;
                    }
                }
            }
        }
        return potentialMinionDamage;
    }

    private double getBuildingDamageForPoint(Point2D point2D, List<LivingUnit> buildings,
                                             boolean potentialDanger) {
        double towerDamage = game.getGuardianTowerDamage();
        double towerRange = game.getGuardianTowerAttackRange();
        double baseDamage = game.getFactionBaseDamage();
        double baseRange = game.getFactionBaseAttackRange();
        double dangerRatio = potentialDanger ? 1.1 : 1.0;

        double potentialBuildingDamage = 0.0;
        for (LivingUnit lu : buildings) {
            Building building = (Building) lu;
            double distance = point2D.getDistanceTo(building);
            if (building.getType() == BuildingType.FACTION_BASE) {
                if (distance <= baseRange * dangerRatio) {
                    potentialBuildingDamage += baseDamage;
                }
            } else {
                if (distance <= towerRange * dangerRatio) {
                    potentialBuildingDamage += towerDamage;
                }
            }
        }
        return potentialBuildingDamage;
    }


    private List<Point2D> weightCollisions(List<Point2D> movePoints, List<Point2D> attackPoints,
                                           List<Point2D> collisionPoints, Map<String, List<LivingUnit>> objectsMap) {
        List<Point2D> result = new ArrayList<>();
        int counter = 0;
        for (Point2D point : movePoints) {
            if (point == null) {
                counter++;
                continue;
            }
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
                result.add(attackPoints.get(counter));
            }
            counter++;
        }
        return result;
    }

    private void weightFightPoints(List<Point2D> trackPoints, Map<String, List<LivingUnit>> objectsMap,
                                   boolean includeBuildings, boolean eager) {
        for (Point2D point2D : trackPoints) {
            for (Map.Entry<String, List<LivingUnit>> objects : objectsMap.entrySet()) {
                String key = objects.getKey();
                List<LivingUnit> units = objects.getValue();
                if (ENEMY_WIZARDS.equals(key)) {
                    point2D.appendToDamageInterest(getWizardDamageForPoint(point2D, units, eager));

                } else if (ENEMY_MINIONS.equals(key)) {
                    point2D.appendToDamageInterest(getMinionDamageForPoint(point2D, units, eager));
                } else if (includeBuildings && ENEMY_BUILDINGS.equals(key)) {
                    point2D.appendToDamageInterest(getBuildingDamageForPoint(point2D, units, eager));
                }

            }

        }
    }

    private void weightDistanceToWayPoint(List<Point2D> trackPoints, Point2D wayPoint) {
        for (Point2D point2D : trackPoints) {
            point2D.appendToMoveInterest(point2D.getDistanceTo(wayPoint));
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
        if (world.getTickIndex() == 2067) {
            System.out.println("bang");
        }
        initializeStrategy(self, game);
        initializeTick(self, world, game, move);
        List<Point2D> movePoints = getNextMovePoints(new Point2D(self));
        List<Point2D> attackPoints = getNextAttackPoints(new Point2D(self));
        List<Point2D> collisionkPoints = getNextCollisionPoints(new Point2D(self));
        Map<String, List<LivingUnit>> nearest = getNearest();
        //Filter potential collisions here
        List<Point2D> trackPoints = weightCollisions(movePoints, attackPoints, collisionkPoints, nearest);

        Point2D nextWP = getNextWaypoint();
        Point2D prevWP = getPreviousWaypoint();

        if (self.getLife() < self.getMaxLife() * LOW_HP_FACTOR) {
            weightFightPoints(trackPoints, nearest, true, true);
            trackPoints.sort(damageCostComparator);
            if (trackPoints.get(0).getDamageInterest() > 0) {
                weightDistanceToWayPoint(trackPoints, prevWP);
                trackPoints.sort(moveCostComparator);
                goToPoint(trackPoints, null);
                return;
            }
        }

        List<LivingUnit> enemies = nearest.get(ENEMIES);
        LivingUnit closestEnemy = enemies.size() > 0 ? enemies.get(0) : null;

        if (closestEnemy != null && self.getDistanceTo(closestEnemy) < self.getCastRange()) {
            Point2D currentPosition = new Point2D(self);
            trackPoints.add(currentPosition);
            weightFightPoints(trackPoints, nearest, false, false);
            trackPoints.sort(damageCostComparator);
            Point2D target = trackPoints.get(0);
            //Most safe point is still unsafe
            if (target.getDamageInterest() > 0) {
                weightDistanceToWayPoint(trackPoints, prevWP);
                trackPoints.sort(moveCostComparator);
                target = trackPoints.get(0);
                goTo(target, closestEnemy);
                previousHp = self.getLife();
                return;
            }
            
            target = null;

            List<Enemy> enemiesToAtack = new ArrayList<>();
            for (LivingUnit lu : enemies) {
                double distanceToSelf = lu.getDistanceTo(self);
                if (distanceToSelf <= self.getCastRange()) {
                    Enemy e = new Enemy(game, lu, distanceToSelf, self.getCastRange());
                    enemiesToAtack.add(e);
                } else {
                    break;
                }
            }
            enemiesToAtack.sort(attackInterestCostComparator);
            LivingUnit targetEnemy = enemiesToAtack.get(0).getUnit();
            goTo(target, targetEnemy);
            previousHp = self.getLife();
        } else {
            weightDistanceToWayPoint(trackPoints, nextWP);
            trackPoints.sort(moveCostComparator);
            goToPoint(trackPoints, null);
        }
    }

    private void goToPoint(List<Point2D> trackPoints, LivingUnit enemy) {
        if (trackPoints.size() > 0) {
            Point2D target = trackPoints.get(0);
            goTo(target, enemy);
        } else {
            //TODO(tdurakov): HOW???
        }


    }


    /**
     * Инциализируем стратегию.
     * <p/>
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
     * <p/>
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
    private void goTo(Point2D point, LivingUnit target) {
        double attackAngle;
        if (target != null) {
            attackAngle = self.getAngleTo(target);

            if (StrictMath.abs(attackAngle) < game.getStaffSector() / 2.0D) {
                move.setCastAngle(attackAngle);
                move.setAction(ActionType.MAGIC_MISSILE);
                move.setMinCastDistance(target.getDistanceTo(self) - target.getRadius() + game.getMagicMissileRadius());
            }
        } else {
            attackAngle = self.getAngleTo(point.getX(), point.getY());
        }
        move.setTurn(attackAngle);
        if (point != null) {
            double moveAngle = self.getAngleTo(point.x, point.y);
            double[] speed = getSpeedForStrafe(moveAngle);
            move.setSpeed(speed[0]);
            move.setStrafeSpeed(speed[1]);
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
            double o1Distance = self.getDistanceTo(o1);
            double o2Distance = self.getDistanceTo(o2);
            if (o1Distance < o2Distance) {
                return -1;
            }
            if (o1Distance > o2Distance) {
                return 1;
            }
            return 0;
        }
    }

    public static final class AttackInterestCostComparator implements Comparator<Enemy> {

        @Override
        public int compare(Enemy e1, Enemy e2) {
            if (e1.getInterest() < e2.getInterest()) {
                return 1;
            }
            if (e1.getInterest() > e2.getInterest()) {
                return -1;
            }
            return 0;
        }
    }


    public static final class MoveCostComparator implements Comparator<Point2D> {

        @Override
        public int compare(Point2D o1, Point2D o2) {
            if (o1.getMoveInterest() < o2.getMoveInterest()) {
                return -1;
            }
            if (o1.getMoveInterest() > o2.getMoveInterest()) {
                return 1;
            }
            return 0;
        }
    }

    public static final class DamageCostComparator implements Comparator<Point2D> {

        @Override
        public int compare(Point2D o1, Point2D o2) {
            if (o1.getDamageInterest() < o2.getDamageInterest()) {
                return -1;
            }
            if (o1.getDamageInterest() > o2.getDamageInterest()) {
                return 1;
            }
            return 0;
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

    private static final class Enemy {
        private LivingUnit unit;
        private double interest = 1.0;


        private Enemy(Game game, LivingUnit unit, double distanceToSelf, double castRange) {
            this.unit = unit;
            double healthRatio = 1.01 - unit.getLife() / unit.getMaxLife() * 1.0;
            interest *= healthRatio;
            double distanceRatio = 1.0 + distanceToSelf / castRange;
            interest *= distanceRatio;
            if (unit instanceof Wizard) {

                if (unit.getLife() <= game.getStaffDamage()) {
                    interest *= game.getWizardEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getWizardEliminationScoreFactor();
                    interest *= 10.0;
                } else {
                    interest = game.getWizardDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getWizardDamageScoreFactor();
                }
            } else if (unit instanceof Building) {
                if (unit.getLife() <= game.getStaffDamage()) {
                    interest *= game.getBuildingEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getBuildingEliminationScoreFactor();
                    interest *= 10.0;
                } else {
                    interest *= game.getBuildingDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getBuildingDamageScoreFactor();
                }
            } else if (unit instanceof Minion) {
                if (unit.getLife() <= game.getStaffDamage()) {
                    interest *= game.getMinionEliminationScoreFactor() == 0.0 ? 1.0 : 1 + game.getMinionEliminationScoreFactor();
                    interest *= 10.0;
                } else {
                    interest *= game.getMinionDamageScoreFactor() == 0.0 ? 1.0 : 1 + game.getMinionDamageScoreFactor();
                }
            }

        }

        public double getInterest() {
            return interest;
        }

        public LivingUnit getUnit() {
            return unit;
        }
    }

    /**
     * Вспомогательный класс для хранения позиций на карте.
     */
    private static final class Point2D {
        private final double x;
        private final double y;
        private double moveInterest = 0.0;
        private double attackInterest = 0.0;
        private double damageInterest = 0.0;
        private List<Enemy> enemies = new ArrayList<>();

        private Point2D(Unit unit) {
            this.x = unit.getX();
            this.y = unit.getY();
        }

        private Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public List<Enemy> getEnemies() {
            return enemies;
        }

        public void appendToEnemies(Enemy e) {
            enemies.add(e);
        }

        public void appendToMoveInterest(double moveInterest) {
            this.moveInterest += moveInterest;
        }

        public void appendToAttackInterest(double attackInterest) {
            this.attackInterest += attackInterest;
        }

        public void appendToDamageInterest(double damageInterest) {
            this.damageInterest += damageInterest;
        }


        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getMoveInterest() {
            return moveInterest;
        }

        public double getAttackInterest() {
            return attackInterest;
        }

        public double getDamageInterest() {
            return damageInterest;
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