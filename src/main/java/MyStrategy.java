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

    private double[] getAngles() {
        double[] angles = new double[36];
        double sector = StrictMath.PI / 36.0d;
        for (int i = 0; i < ANGLES_FACTOR; i++) {
            angles[i] = sector * i;
        }
        return angles;
    }


    private Point2D[] getNextPoints(Point2D point) {
        Point2D[] points = new Point2D[ANGLES_FACTOR];
        int i = 0;
        for (double angle : angles) {
            double x = StrictMath.cos(angle) * MOVE_RADIUS;
            double y = StrictMath.sin(angle) * MOVE_RADIUS;
            Point2D point2d =  new Point2D(point.x + x, point.y + y);
            if (point2d.isValid()) {
                points[i++] = point2d;
            }
        }
        return points;
    }


    private double[] getSpeedForStrafe(double obstacleAngle) {
        double moveSpeed = 0.0;
        double strafeSpeed = 0.0;
        double moveFactor = StrictMath.cos(obstacleAngle);
        double strafeFactor = -StrictMath.sin(obstacleAngle);
        moveSpeed = game.getWizardForwardSpeed() * moveFactor * 2.0;
        strafeSpeed = game.getWizardStrafeSpeed() * strafeFactor * 2.0;
        return new double[]{moveSpeed, strafeSpeed};
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
        Point2D[] trackPoints = getNextPoints(new Point2D(self));
        LivingUnit[] nearest = getNearest();
        LivingUnit nearestTarget = nearest[1];
        LivingUnit nearestObstacle = nearest[0];
        self.
        if (nearestObstacle != null) {
            double obstacleDistance = self.getDistanceTo(nearestObstacle);
            double obstacleAngle = self.getAngleTo(nearestObstacle);
            if (obstacleDistance <= self.getRadius() * 3.0) {
                double[] speeds = getSpeedForStrafe(obstacleAngle);
                move.setStrafeSpeed(speeds[1]);
                move.setSpeed(speeds[0]);
            }
        }

        // Если осталось мало жизненной энергии, отступаем к предыдущей ключевой точке на линии.
        if (self.getLife() < self.getMaxLife() * LOW_HP_FACTOR) {
            goTo(getPreviousWaypoint());
            return;
        }

        if (nearestTarget != null) {
            double distance = self.getDistanceTo(nearestTarget);


            // ... и он в пределах досягаемости наших заклинаний, ...
            if (distance <= self.getCastRange()) {
                // Постоянно двигаемся из-стороны в сторону, чтобы по нам было сложнее попасть.
                // Считаете, что сможете придумать более эффективный алгоритм уклонения? Попробуйте! ;)
                move.setStrafeSpeed(random.nextBoolean() ? game.getWizardStrafeSpeed() : -game.getWizardStrafeSpeed());
                move.setSpeed(0);
                double angle = self.getAngleTo(nearestTarget);

                // ... то поворачиваемся к цели.
                move.setTurn(angle);

                // Если цель перед нами, ...
                if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
                    // ... то атакуем.
                    move.setAction(ActionType.MAGIC_MISSILE);
                    move.setCastAngle(angle);
                    move.setMinCastDistance(distance - nearestTarget.getRadius() + game.getMagicMissileRadius());
                }

                return;
            }
        }

        // Если нет других действий, просто продвигаемся вперёд.
        goTo(getNextWaypoint());
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
    private LivingUnit[] getNearest() {
        List<LivingUnit> targets = new ArrayList<>();
        targets.addAll(Arrays.asList(world.getBuildings()));
        targets.addAll(Arrays.asList(world.getWizards()));
        targets.addAll(Arrays.asList(world.getMinions()));
        LivingUnit nearestTarget = null;
        LivingUnit nearestObstacle = null;
        double nearestTargetDistance = Double.MAX_VALUE;
        double nearestObstacleDistance = Double.MAX_VALUE;
        for (LivingUnit target : targets) {
            if (self.getId() == target.getId()) {
                continue;
            }
            double distance = self.getDistanceTo(target);
            if (target.getFaction() == Faction.NEUTRAL || target.getFaction() == self.getFaction()) {
                if (distance < nearestObstacleDistance) {
                    nearestObstacle = target;
                    nearestObstacleDistance = distance;
                }
            } else if (distance < nearestTargetDistance) {
                nearestTarget = target;
                nearestTargetDistance = distance;
            }

        }
        Tree[] treeArray = world.getTrees();

        for (Tree tree : treeArray) {
            double distance = self.getDistanceTo(tree);
            if (distance < nearestObstacleDistance) {
                nearestObstacle = tree;
                nearestObstacleDistance = distance;
            }
        }
        return new LivingUnit[]{nearestObstacle, nearestTarget};
    }

    /**
     * Вспомогательный класс для хранения позиций на карте.
     */
    private static final class Point2D {
        private final double x;
        private final double y;

        private Point2D(Unit unit) {
            this.x = unit.getX();
            this.y = unit.getY();
        }

        private Point2D(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
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
