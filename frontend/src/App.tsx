import './App.css'
import Session from './Session'

// The app is the daily session loop (issue #19): a warm-up that completes the day, then an
// optional main exercise and uncapped continuation. Everything shipped before it - content
// loading (#14), attempt persistence (#15), judging and the hint ladder (#16), warm-up reps
// (#18) - is machinery this loop puts to work. The whole shell lives in Session.
function App() {
  return <Session />
}

export default App
