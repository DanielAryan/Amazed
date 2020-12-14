package amazed.solver;

import amazed.maze.Maze;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <code>ForkJoinSolver</code> implements a solver for
 * <code>Maze</code> objects using a fork/join multi-thread
 * depth-first search.
 * <p>
 * Instances of <code>ForkJoinSolver</code> should be run by a
 * <code>ForkJoinPool</code> object.
 */


public class ForkJoinSolver
extends SequentialSolver
{
	//A list of every ForkJoinSolver spawned by this thread. Used when some player has found the 
	//result to join every ForkJoinSolver
	private List<ForkJoinSolver> subSolvers = new ArrayList<ForkJoinSolver>();
	//A thread-safe boolean that indicates to every player when a ForkJoinSolver has found the goal
	private static AtomicBoolean goalHasBeenFound=new AtomicBoolean();

	/**
	 * Creates a solver that searches in <code>maze</code> from the
	 * start node to a goal.
	 *
	 * @param maze   the maze to be searched
	 */
	public ForkJoinSolver(Maze maze)
	{
		super(maze);
	}

	/**
	 * Overrides the initStructures in the super class, replacing them with 
	 * thread-safe data structures
	 */
	@Override
	protected void initStructures() {
		visited = new ConcurrentSkipListSet<>();
		predecessor = new HashMap<>();
		frontier = new Stack<>();
	}

	/**
	 * Creates a solver that searches in <code>maze</code> from the
	 * start node to a goal, forking after a given number of visited
	 * nodes.
	 *
	 * @param maze        the maze to be searched
	 * @param forkAfter   the number of steps (visited nodes) after
	 *                    which a parallel task is forked; if
	 *                    <code>forkAfter &lt;= 0</code> the solver never
	 *                    forks new tasks
	 */
	public ForkJoinSolver(Maze maze, int forkAfter)
	{
		this(maze);
		this.forkAfter = forkAfter;
	}

	/**
	 * A constructor used when forking (i.e creating additional ForkJoinSolvers)
	 * Used to make sure that the forked threads share the same visited and predecessor objects
	 * So that they can share information with each other
	 * @param maze the maze to be searched
	 * @param forkAfterthe number of steps (visited nodes) after
	 *                    which a parallel task is forked; if
	 *                    <code>forkAfter &lt;= 0</code> the solver never
	 *                    forks new tasks
	 * 
	 * @param start The starting position of this ForkJoinSolver
	 * @param visited The set of visited nodes, shared with every other ForkJoinSolver
	 */
	public ForkJoinSolver(Maze maze, int forkAfter, int start, Set<Integer> visited){//,Map<Integer, Integer> predecessor) {
		this(maze,forkAfter);
		this.start=start;
		this.visited=visited;
	}

	/**
	 * Searches for and returns the path, as a list of node
	 * identifiers, that goes from the start node to a goal node in
	 * the maze. If such a path cannot be found (because there are no
	 * goals, or all goals are unreacheable), the method returns
	 * <code>null</code>.
	 *
	 * @return   the list of node identifiers from the start node to a
	 *           goal node in the maze; <code>null</code> if such a path cannot
	 *           be found.
	 */
	@Override
	public List<Integer> compute()
	{
		return parallelSearch();
	}

	private List<Integer> parallelSearch()
	{
		//A counter which tracks how many steps a ForkJoinSolver has taken since last forking
		int step = 0;
		// one player active on the maze at start
		int player;
		//We make sure that the starting node isn't visited before spawning a solver
		player = maze.newPlayer(start);
		// start with start node
		frontier.push(start);
		
			// as long as not all nodes have been processed
			// and as long as no ForkJoinSolver has found the goal
			while (!frontier.empty() && !goalHasBeenFound.get()) {
				// get the new node to process
				int current = frontier.pop();
				//Make sure that the current node hasn't been visited before (already checked for the starting node of new solvers)
				if (visited.add(current)||current==start) {
					// if current node has a goal
					if (maze.hasGoal(current)) {
						//If the goal has been found, signal this to the other ForkJoinSolvers
						//So that we do not have to wait for them to finish on their own
						goalHasBeenFound.set(true);
						// move player to goal
						maze.move(player, current);
						//increment step when the players has moved
						step++;
						// search finished: reconstruct and return path
						//return pathFromTo(maze.start(), current);
						return pathFromTo(start, current);
					}

					// if current node has not been visited yet
					// move player to current node
					maze.move(player, current);
					//increment step when the players has moved
					step++;

					//No need to fork if only one-way
					boolean first = true;

					// for every node nb adjacent to current
					for (int nb: maze.neighbors(current)) {

						// nb can be reached from current (i.e., current is nb's predecessor)
						//visited is not changed immediately, as some other solver may get there first
						//but we always make sure that a solver cant spawn on a visited node
						if(!visited.contains(nb)) {
							predecessor.put(nb, current);

							// add nb to the nodes to be processed
							// if nb has not been already visited,

							//if this is the first neighbor, there is no need to fork, instead the current player
							//will keep going in this direction. Same if the current player hasn't moved forkAfter number
							//of steps yet
							if (first || step<forkAfter) {
								//OBS! Checks that the neighbor isn't visited before moving there, checked when the nb is on top of the frontier
								frontier.push(nb);
								first=false;
							}
							//for other unvisited neighbors, create a new player (i.e a ForkJoinSolver), and fork them
							//also add them to the subSolvers list, which is a list of every "child" player of 
							//this player. This list is used later on when joining the different forks, when
							//the game is over and some player has found the goal. 
							//OBS! We make sure that the neighbor is unvisited before spawning a solver there!
							//OBS! We chose to only fork once when stepafter has been reached, not for every neighbor! 
							else {
								if(visited.add(nb)){
									step=0;
									ForkJoinSolver forkedSolver = new ForkJoinSolver(maze,forkAfter,nb,visited);
									subSolvers.add(forkedSolver);
									forkedSolver.fork();
								}
							}
						}
					}
				}
			}
			// all nodes explored, no goal found
			//Time to join with the other ForkJoinSolvers
			return joinTasks();
		}
	

	/**
	 * When a ForkJoinSolver is done with their tasks (i.e their frontier is empty or some other 
	 * ForkJoinSolver has signaled that they found the goal), the joinTasks method is called. The 
	 * parent ForkJoinSolvers then iterates through each of their children (if any) and joins, to see if any of them
	 * has found the goal. If any of the children has found the goal, return their result. Otherwise, return null
	 */
	private List<Integer> joinTasks() {
		for (ForkJoinSolver solver:subSolvers) {
			List<Integer> result = solver.join();
			if(result!=null) {
				List<Integer> myPath = pathFromTo(start, predecessor.get(solver.start));
				myPath.addAll(result);
				return myPath;
			}
		}
		return null;
	}
}
